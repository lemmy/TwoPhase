package org.lbee;

import org.lbee.instrumentation.VirtualField;
import org.lbee.models.Message;
import org.lbee.models.TwoPhaseMessage;

import java.io.IOException;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class TransactionManager extends Manager implements NamedClient {

    // Config
    private final TransactionManager.TransactionManagerConfiguration config;
    // Resource manager linked to TM
    private final HashSet<String> resourceManagers;
    // Resource manager prepared to commit
    private final HashSet<String> preparedResourceManagers;

    // Number of resource manager prepared to commit
    private int nbPrepared;

    private boolean isAllRegistered = false;

    private final VirtualField specTmPrepared;

    public TransactionManager(Socket socket, TransactionManagerConfiguration config) throws IOException {
        super("TM", socket);

        resourceManagers = new HashSet<>();
        preparedResourceManagers = new HashSet<>();
        // Note: invert comment to introduce bug
        nbPrepared = 0;
//        nbPrepared = 1;
        this.config = config;

        this.specTmPrepared = spec.getVariable("tmPrepared");
    }

    private void reset() throws IOException {
        resourceManagers.clear();
        preparedResourceManagers.clear();
        nbPrepared = 0;
        specTmPrepared.clear();
        spec.commitChanges("TMReset");
    }

    @Override
    public void run() throws IOException {
        // Check eventual received message
        super.run();

        // Waiting for all resource manager registered
        if (resourceManagers.size() < config.nResourceManager)
            return;

        // Do just once
        if (!isAllRegistered) {
            System.out.println("All expected resource managers are registered.");
            String strResourceManagers = this.resourceManagers.stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(", "));
            String rmValue = "{" + strResourceManagers + "}";
            isAllRegistered = true;
        }

        if (checkCommit())
            this.commit();
    }

    protected void receive(Message message) throws IOException {
        switch (message.getContent()) {
            case "Register" -> this.receivedRegister(message.getFrom());
            case "Prepared" -> this.receivePrepared(message.getFrom());
            /* Nothing to do */
            default -> {}
        }
    }

    protected void receivedRegister(String resourceManagerName) {
        System.out.printf("Register a new resource manager: %s.\n", resourceManagerName);
        this.resourceManagers.add(resourceManagerName);
    }

    protected boolean checkCommit()  {
//        return this.preparedResourceManagers.containsAll(this.resourceManagers) || this.config.commitAnyway;
        return this.nbPrepared == this.resourceManagers.size();
    }

    /**
     * @TLAAction TMCommit
     */
    private void commit() throws IOException {
        // Notify
        specMessages.add(Map.of("type", "Commit"));
        spec.commitChanges("TMCommit");

        for (String rmName : resourceManagers)
            this.networkManager.send(new Message(this.getName(), rmName, TwoPhaseMessage.COMMIT.toString(), 0));

        // Display message
        System.out.println(TwoPhaseMessage.COMMIT + ".");

        // Shutdown
        this.shutdown();
    }

    /**
     * @TLAAction TMRcvPrepared(r)
     */
    public void receivePrepared(String sender) throws IOException {
        /* Search receive prepared resource manager in resource manager set */
        Optional<String> optionalResourceManager = resourceManagers.stream().filter(rmName -> rmName.equals(sender)).findFirst();
        /* If it doesn't exist do nothing */
        if (optionalResourceManager.isEmpty())
            return;

        /* Add prepared resource manager to prepared set */
        String rmName = optionalResourceManager.get();
        preparedResourceManagers.add(rmName);
        nbPrepared++;
        specTmPrepared.add(rmName);
        spec.commitChanges("TMRcvPrepared");
    }

    /**
     * Configuration of a resource manager
     * @param timeout Is resource manager should fail, invoke an unknown exception
     * @param commitAnyway Commit even if some RM are not prepared (introduce error in implementation)
     */
    record TransactionManagerConfiguration(int nResourceManager, int timeout, boolean commitAnyway) {
        @Override
        public String toString() {
            return "TransactionManagerConfiguration{" +
                    "timeout=" + timeout +
                    '}';
        }
    }

}
