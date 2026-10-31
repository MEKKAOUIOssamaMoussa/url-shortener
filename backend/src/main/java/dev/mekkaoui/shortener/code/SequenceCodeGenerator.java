package dev.mekkaoui.shortener.code;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Sequence-based code generator that fetches the next sequence value from PostgreSQL,
 * obfuscates it via a keyed Feistel cycle-walking permutation, and encodes it into a
 * fixed-length 7-character Base62 short URL code.
 * <p>
 * Database sequences are non-transactional: failed or rolled-back inserts advance the sequence
 * and leave gaps, which are completely harmless for short codes.
 * </p>
 */
public class SequenceCodeGenerator implements CodeGenerator {

    private static final int CODE_LENGTH = 7;
    private static final String NEXTVAL_SQL = "select nextval('short_code_seq')";

    private final JdbcTemplate jdbcTemplate;
    private final FeistelCipher cipher;

    public SequenceCodeGenerator(JdbcTemplate jdbcTemplate, FeistelCipher cipher) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
    }

    @Override
    public String generate() {
        Long seq = jdbcTemplate.queryForObject(NEXTVAL_SQL, Long.class);
        if (seq == null) {
            throw new IllegalStateException("Failed to retrieve next sequence value from database");
        }
        long scrambled = cipher.encrypt(seq);
        return Base62.encode(scrambled, CODE_LENGTH);
    }
}
