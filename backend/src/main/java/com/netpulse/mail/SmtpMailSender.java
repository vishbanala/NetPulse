package com.netpulse.mail;

import java.util.Properties;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

@Component
public class SmtpMailSender implements MailSender {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;

    public SmtpMailSender(Environment environment) {
        this.host = trim(environment.getProperty("netpulse.smtp.host", ""));
        this.port = parsePort(environment.getProperty("netpulse.smtp.port", "587"));
        this.username = trim(environment.getProperty("netpulse.smtp.username", ""));
        this.password = environment.getProperty("netpulse.smtp.password", "");
        this.from = trim(environment.getProperty("netpulse.smtp.from", ""));
        this.startTls = Boolean.parseBoolean(environment.getProperty("netpulse.smtp.starttls", "true"));
    }

    @Override
    public boolean isConfigured() {
        return !host.isEmpty() && !from.isEmpty();
    }

    @Override
    public void send(String toEmail, String subject, String textBody) throws MailSendException {
        if (!isConfigured()) {
            throw new MailSendException("Email delivery is not configured");
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", String.valueOf(!username.isEmpty()));
            props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
            Session session;
            if (!username.isEmpty()) {
                session = Session.getInstance(props, new jakarta.mail.Authenticator() {
                    @Override
                    protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new jakarta.mail.PasswordAuthentication(username, password);
                    }
                });
            } else {
                session = Session.getInstance(props);
            }
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(toEmail));
            message.setSubject(subject, "UTF-8");
            message.setText(textBody, "UTF-8");
            Transport.send(message);
        } catch (Exception ex) {
            throw new MailSendException("Failed to send email", ex);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return 587;
        }
    }
}
