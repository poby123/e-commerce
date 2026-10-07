package com.ecommerce.user.admin;

import com.ecommerce.user.domain.User;
import com.ecommerce.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates the first ADMIN account from configuration when it does not exist yet, so a fresh environment can
 * register products without editing the database by hand. Off unless ecommerce.bootstrap-admin.email is set.
 */
@Component
@ConditionalOnProperty("ecommerce.bootstrap-admin.email")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final Properties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(Properties properties, UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @ConfigurationProperties("ecommerce.bootstrap-admin")
    public record Properties(String email, String password) {
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.email().strip().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            return;
        }
        try {
            userRepository.saveAndFlush(User.registerAdmin(email, passwordEncoder.encode(properties.password()), "Admin"));
            log.info("Created bootstrap admin {}", email);
        } catch (DataIntegrityViolationException e) {
            // Another instance created it at the same time.
        }
    }
}
