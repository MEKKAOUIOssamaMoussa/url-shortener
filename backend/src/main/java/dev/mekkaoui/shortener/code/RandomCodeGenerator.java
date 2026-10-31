package dev.mekkaoui.shortener.code;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class RandomCodeGenerator implements CodeGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int CODE_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(chars);
    }
}
