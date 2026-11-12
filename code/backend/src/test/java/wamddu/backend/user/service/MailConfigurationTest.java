package wamddu.backend.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mail must work from SMTP_USERNAME and SMTP_PASSWORD alone, without a Spring profile, as on the
 * deploy server. Tests shadow the main application.properties, so this reads the main file directly.
 */
class MailConfigurationTest {
    static List<String> mainMailProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(in);
        }
        List<String> mail = new ArrayList<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("spring.mail.") || name.startsWith("app.mail.")) {
                mail.add(name + "=" + properties.getProperty(name));
            }
        }
        return mail;
    }

    ApplicationContextRunner runner() throws IOException {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues(mainMailProperties().toArray(String[]::new));
    }

    @Test
    void naverSmtpIsConfiguredFromTheTwoVariablesWithoutAProfile() throws IOException {
        runner().withPropertyValues("SMTP_USERNAME=sender@example.test", "SMTP_PASSWORD=app-password")
                .run(context -> {
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    assertThat(sender.getHost()).isEqualTo("smtp.naver.com");
                    assertThat(sender.getPort()).isEqualTo(587);
                    assertThat(sender.getUsername()).isEqualTo("sender@example.test");
                    assertThat(sender.getPassword()).isEqualTo("app-password");
                    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.starttls.required")).isEqualTo("true");
                    assertThat(context.getEnvironment().getProperty("app.mail.from")).isEqualTo("sender@example.test");
                });
    }

    @Test
    void withoutVariablesTheSenderAddressAndPasswordStayEmpty() throws IOException {
        // EmailVerificationService refuses to send (MAIL_NOT_CONFIGURED) when these are blank.
        runner().run(context -> {
            assertThat(context.getEnvironment().getProperty("app.mail.from")).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.mail.password")).isEmpty();
        });
    }
}
