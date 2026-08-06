package org.telegram.messenger;

public class LanguageDetector {
    public interface StringCallback {
        void run(String str);
    }
    public interface ExceptionCallback {
        void run(Exception e);
    }

    public static boolean hasSupport() {
        return false;
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail) {
        if (onFail != null) onFail.run(new UnsupportedOperationException("ML Kit removed"));
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail, boolean initializeFirst) {
        detectLanguage(text, onSuccess, onFail);
    }
}
