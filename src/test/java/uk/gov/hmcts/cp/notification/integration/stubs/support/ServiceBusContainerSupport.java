package uk.gov.hmcts.cp.notification.integration.stubs.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class ServiceBusContainerSupport {
    public static final String COMMAND_QUEUE = "ng-send-email";
    public static final String SLICE_COMMAND_QUEUE = "ng-send-email-slice";
    public static final String LETTER_COMMAND_QUEUE = "ng-send-letter";
    public static final String SLICE_LETTER_COMMAND_QUEUE = "ng-send-letter-slice";
    public static final String RESULT_QUEUE = "ng-result-correspondence";
    public static final String SLICE_RESULT_QUEUE = "ng-result-slice";

    // Images are pinned to explicit immutable version tags so the IT suite is reproducible — the floating
    // tags (servicebus-emulator:latest, mssql/server:2025-latest) drift under us and cause spontaneous
    // breakage. Override for a controlled bump via -Dcp.test.<x>.image=... or CP_TEST_<X>_IMAGE.
    //
    // Apple Silicon (arm64): the emulator tag below is multi-arch and runs natively. mssql/server has no
    // arm64 build, so by default it runs under Docker Desktop emulation (works, slower). There is no arm64
    // build of full SQL Server; the only arm64-capable SQL image is mcr.microsoft.com/azure-sql-edge
    // (a distinct, now-deprecated SQL-Server-2022-based engine). To try it natively, override
    // CP_TEST_MSSQL_IMAGE=mcr.microsoft.com/azure-sql-edge:<tag> — validate it against the emulator first,
    // it is not a guaranteed drop-in for the pinned mssql/server image.
    private static final String SERVICE_BUS_IMAGE_DEFAULT =
            "mcr.microsoft.com/azure-messaging/servicebus-emulator:1.1.2";
    private static final String MSSQL_IMAGE_DEFAULT =
            "mcr.microsoft.com/mssql/server:2025-CU7-ubuntu-22.04";

    private static final String SERVICE_BUS_IMAGE =
            resolveImage("cp.test.servicebus.image", "CP_TEST_SERVICEBUS_IMAGE", SERVICE_BUS_IMAGE_DEFAULT);
    private static final String MSSQL_IMAGE =
            resolveImage("cp.test.mssql.image", "CP_TEST_MSSQL_IMAGE", MSSQL_IMAGE_DEFAULT);

    private static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    private static final MSSQLServerContainer MSSQL = new MSSQLServerContainer(
            DockerImageName.parse(MSSQL_IMAGE).asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"))
            .acceptLicense()
            .withNetwork(NETWORK);

    @SuppressWarnings("resource")
    private static final ServiceBusEmulatorContainer SERVICE_BUS = new ServiceBusEmulatorContainer(
            DockerImageName.parse(SERVICE_BUS_IMAGE)
                    .asCompatibleSubstituteFor("mcr.microsoft.com/azure-messaging/servicebus-emulator"))
            .acceptLicense()
            .withConfig(MountableFile.forClasspathResource("servicebus-config.json"))
            .withNetwork(NETWORK)
            .withMsSqlServerContainer(MSSQL);

    private static final Logger MSSQL_DRIVER_LOG = Logger.getLogger("com.microsoft.sqlserver.jdbc");

    static {
        MSSQL_DRIVER_LOG.setLevel(Level.SEVERE);
        MSSQL.start();
        SERVICE_BUS.start();
    }

    private ServiceBusContainerSupport() {
    }

    public static String getConnectionString() {
        return SERVICE_BUS.getConnectionString();
    }

    public static void registerProperties(final DynamicPropertyRegistry registry) {
        registerProperties(registry, COMMAND_QUEUE, LETTER_COMMAND_QUEUE);
    }

    // The sliced consumer tests listen on their own queues, so they never take a command meant for the full
    // application context, which may be running in the same JVM.
    public static void registerSliceProperties(final DynamicPropertyRegistry registry) {
        registerProperties(registry, SLICE_COMMAND_QUEUE, SLICE_LETTER_COMMAND_QUEUE);
    }

    private static void registerProperties(
            final DynamicPropertyRegistry registry, final String commandQueue, final String letterCommandQueue) {
        registry.add("cp.notification.servicebus.connection-string", ServiceBusContainerSupport::getConnectionString);
        registry.add("cp.notification.servicebus.command-queue", () -> commandQueue);
        registry.add("cp.notification.servicebus.letter-command-queue", () -> letterCommandQueue);
    }

    private static String resolveImage(final String propertyKey, final String envKey, final String defaultImage) {
        final String fromProperty = System.getProperty(propertyKey);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        final String fromEnv = System.getenv(envKey);
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : defaultImage;
    }
}
