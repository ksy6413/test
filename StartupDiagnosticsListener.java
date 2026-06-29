package ksy.game.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.*;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

// Logs startup diagnostics to help troubleshoot environment, JVM, command-line, and Spring Boot configuration issues.
@Slf4j
public final class StartupDiagnosticsListener implements ApplicationListener<ApplicationPreparedEvent> {
    private static final String BOOT_CONFIGURATION_PROPERTIES_SOURCE_NAME = "configurationProperties";

    @Override
    public void onApplicationEvent(ApplicationPreparedEvent event) {
        if (!log.isInfoEnabled()) {
            return;
        }
        ConfigurableEnvironment environment = event.getApplicationContext().getEnvironment();
        log.info("Startup diagnostics report:\n{}", format(environment, event.getArgs()));
    }

    private static String format(ConfigurableEnvironment environment, String[] commandLineArguments) {
        StringBuilder output = new StringBuilder(16_384);

        appendSection(output, "Environment Variables");
        appendEnvironmentVariables(output);

        appendSection(output, "System Properties");
        appendSystemProperties(output);

        appendSection(output, "Command-Line Arguments");
        appendCommandLineArguments(output, commandLineArguments);

        appendSection(output, "Effective Spring Boot Configuration");
        appendEffectiveSpringProperties(output, environment);

        return output.toString();
    }

    private static void appendEnvironmentVariables(StringBuilder output) {
        Map<String, String> environmentVariables = new TreeMap<>(System.getenv());

        for (Map.Entry<String, String> entry : environmentVariables.entrySet()) {
            if ("PATH".equalsIgnoreCase(entry.getKey())) {
                appendPath(output, entry.getKey(), entry.getValue());
            } else {
                appendProperty(output, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void appendSystemProperties(StringBuilder output) {
        Properties systemProperties = System.getProperties();
        Map<String, String> sortedProperties = new TreeMap<>();

        for (String propertyName : systemProperties.stringPropertyNames()) {
            sortedProperties.put(propertyName, systemProperties.getProperty(propertyName));
        }

        for (Map.Entry<String, String> entry : sortedProperties.entrySet()) {
            if ("java.library.path".equals(entry.getKey())) {
                appendPath(output, entry.getKey(), entry.getValue());
            } else {
                appendProperty(output, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void appendCommandLineArguments(StringBuilder output, String[] commandLineArguments) {
        if (commandLineArguments.length == 0) {
            output.append("  <none>").append(System.lineSeparator());
            return;
        }

        for (int i = 0; i < commandLineArguments.length; i++) {
            output.append("  ")
                    .append(i + 1)
                    .append(". ")
                    .append(commandLineArguments[i])
                    .append(System.lineSeparator());
        }
    }

    private static void appendSection(StringBuilder output, String title) {
        if (output.length() > 0) {
            output.append(System.lineSeparator());
        }
        output.append('[').append(title).append(']').append(System.lineSeparator());
    }

    private static void appendProperty(StringBuilder output, String key, String value) {
        output.append("  ").append(key).append(" = ").append(value).append(System.lineSeparator());
    }

    private static void appendPath(StringBuilder output, String propertyName, String value) {
        if (value == null || value.isEmpty()) {
            appendProperty(output, propertyName, "<not set>");
            return;
        }

        String[] entries = value.split(Pattern.quote(File.pathSeparator), -1);
        output.append("  ")
                .append(propertyName)
                .append(" (")
                .append(entries.length)
                .append(entries.length == 1 ? " entry):" : " entries):")
                .append(System.lineSeparator());

        for (int i = 0; i < entries.length; i++) {
            output.append("    ")
                    .append(i + 1)
                    .append(". ")
                    .append(entries[i].isEmpty() ? "<empty entry>" : entries[i])
                    .append(System.lineSeparator());
        }
    }

    private static void appendEffectiveSpringProperties(StringBuilder output, ConfigurableEnvironment environment) {
        appendProperty(output, "active.profiles", Arrays.toString(environment.getActiveProfiles()));
        Set<String> propertyNames = new TreeSet<>();

        for (PropertySource<?> propertySource : environment.getPropertySources()) {
            if (shouldExcludeFromSpringBootConfiguration(propertySource)) {
                continue;
            }

            if (!(propertySource instanceof EnumerablePropertySource<?>)) {
                continue;
            }
            EnumerablePropertySource<?> enumerablePropertySource = (EnumerablePropertySource<?>) propertySource;
            Collections.addAll(propertyNames, enumerablePropertySource.getPropertyNames());
        }

        for (String propertyName : propertyNames) {
            String effectiveValue = environment.getProperty(propertyName);

            if (effectiveValue != null) {
                appendProperty(output, propertyName, effectiveValue);
            }
        }
    }

    private static boolean shouldExcludeFromSpringBootConfiguration(PropertySource<?> propertySource) {
        String propertySourceName = propertySource.getName();
        return StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(propertySourceName)
                || StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(propertySourceName)
                || CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME.equals(propertySourceName)
                || BOOT_CONFIGURATION_PROPERTIES_SOURCE_NAME.equals(propertySourceName);
    }
}