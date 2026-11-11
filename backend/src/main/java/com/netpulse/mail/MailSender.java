package com.netpulse.mail;

public interface MailSender {

    boolean isConfigured();

    void send(String toEmail, String subject, String textBody) throws MailSendException;

    class MailSendException extends Exception {
        public MailSendException(String message) {
            super(message);
        }

        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
