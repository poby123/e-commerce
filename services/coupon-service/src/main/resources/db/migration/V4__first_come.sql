-- First-come policies are issued through Redis + Kafka (FirstComeIssuer), never through the DB issue path:
-- mixing both would let the two counters disagree.
ALTER TABLE coupon_policies ADD COLUMN first_come BOOLEAN NOT NULL DEFAULT FALSE;
