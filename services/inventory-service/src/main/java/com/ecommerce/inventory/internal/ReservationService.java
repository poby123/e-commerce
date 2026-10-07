package com.ecommerce.inventory.internal;

import com.ecommerce.common.error.BusinessException;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.StockRepository;
import com.ecommerce.inventory.domain.StockReservation;
import com.ecommerce.inventory.domain.StockReservationRepository;
import com.ecommerce.inventory.error.InventoryErrorCode;
import com.ecommerce.inventory.internal.ReservationDtos.Item;
import com.ecommerce.inventory.internal.ReservationDtos.ReservationResponse;
import com.ecommerce.inventory.internal.ReservationDtos.ReserveRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Reserve = take stock for an order, all items or none. Release = give it back (compensation).
 * Both are idempotent per orderId, because the caller retries on timeouts and cannot know whether
 * the first attempt went through.
 */
@Service
public class ReservationService {

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;
    private final TransactionTemplate transaction;

    public ReservationService(StockRepository stockRepository, StockReservationRepository reservationRepository,
                              TransactionTemplate transaction) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.transaction = transaction;
    }

    public ReservationResponse reserve(ReserveRequest request) {
        List<Item> items = sortedByProduct(request.items());
        try {
            return transaction.execute(status -> {
                List<StockReservation> existing = reservationRepository.findByOrderIdOrderByProductId(request.orderId());
                if (!existing.isEmpty()) {
                    return replay(request.orderId(), existing);
                }
                // Claim the orderId first: a concurrent duplicate blocks on the unique key and then fails,
                // before it has touched any stock row.
                reservationRepository.saveAllAndFlush(items.stream()
                        .map(item -> new StockReservation(request.orderId(), item.productId(), item.quantity()))
                        .toList());
                // Rows are locked in productId order. Two orders for {A, B} and {B, A} would otherwise lock
                // A then B and B then A, and deadlock.
                for (Item item : items) {
                    if (stockRepository.decrease(item.productId(), item.quantity()) == 0) {
                        throw new BusinessException(InventoryErrorCode.INSUFFICIENT_STOCK,
                                "Not enough stock for product " + item.productId());
                    }
                }
                return new ReservationResponse(request.orderId(), ReservationStatus.RESERVED.name(), items);
            });
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race against the same orderId: the winner's result is the answer.
            return transaction.execute(status ->
                    replay(request.orderId(), reservationRepository.findByOrderIdOrderByProductId(request.orderId())));
        }
    }

    /** Gives the stock back. Unknown orderId or already released: nothing to do. */
    public void release(String orderId) {
        transaction.executeWithoutResult(status -> {
            for (StockReservation reservation : reservationRepository.findByOrderIdOrderByProductId(orderId)) {
                if (reservationRepository.markReleased(reservation.getId()) == 1) {
                    stockRepository.increase(reservation.getProductId(), reservation.getQuantity());
                }
            }
        });
    }

    private ReservationResponse replay(String orderId, List<StockReservation> reservations) {
        if (reservations.getFirst().getStatus() == ReservationStatus.RELEASED) {
            throw new BusinessException(InventoryErrorCode.RESERVATION_RELEASED);
        }
        List<Item> items = reservations.stream().map(r -> new Item(r.getProductId(), r.getQuantity())).toList();
        return new ReservationResponse(orderId, ReservationStatus.RESERVED.name(), items);
    }

    private static List<Item> sortedByProduct(List<Item> items) {
        if (new HashSet<>(items.stream().map(Item::productId).toList()).size() != items.size()) {
            throw new BusinessException(InventoryErrorCode.DUPLICATE_ITEM);
        }
        return items.stream().sorted(Comparator.comparing(Item::productId)).toList();
    }
}
