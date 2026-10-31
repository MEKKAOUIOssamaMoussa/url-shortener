package dev.mekkaoui.shortener.code;

import java.security.SecureRandom;

public class RandomCodeGenerator implements CodeGenerator {

    private static final int CODE_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        char[] chars = new char[CODE_LENGTH];
        for (int i = 0; i < CODE_LENGTH; i++) {
            chars[i] = Base62.ALPHABET.charAt(random.nextInt(Base62.ALPHABET.length()));
        }
        return new String(chars);
    }
}
