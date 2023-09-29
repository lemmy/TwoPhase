package org.lbee.protocol;

import org.lbee.instrumentation.trace.TLATracer;
import org.lbee.instrumentation.trace.VirtualField;
import org.lbee.network.NetworkManager;
import org.lbee.network.TimeOutException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TransactionManager extends Manager {
    private final static int RECEIVE_TIMEOUT = 100;
    private final static int ABORT_TIMEOUT = 100;
    // Resource managers managed by TM (as specified in the configuration)
    private final Set<String> resourceManagers;
    // Number of resource managers prepared to commit
    private final Collection<String> preparedRMs;

    private final VirtualField specMessages;
    private final VirtualField specTmPrepared;

    public TransactionManager(NetworkManager networkManager, String name, List<String> resourceManagerNames,
            TLATracer spec) {
        super(name, networkManager, spec);
        this.resourceManagers = new HashSet<>(resourceManagerNames);
        // Even if preparedRMs.size doesn't neccesarily reflect the number of prepared
        // RM when
        // the commit decision was taken, increasing the commit duration might lead to a
        // valid trace because the last RM (not counted by nbPrepared when the commit
        // decision was taken) has time to send its Prepared message before TM send the
        // commit message
        this.preparedRMs = new ArrayList<>();
        // this.preparedRMs = new HashSet<>();
        this.specMessages = spec.getVariableTracer("msgs");
        this.specTmPrepared = spec.getVariableTracer("tmPrepared");
    }

    @Override
    public void run() throws IOException {
        long startTime = System.currentTimeMillis();
        do {
            if (!this.isTerminated()) {
                // block on receiving message until timeout, retry if timeout
                boolean received = false;
                do {
                    try {
                        Message message = networkManager.syncReceive(this.getName(), RECEIVE_TIMEOUT);
                        this.receive(message);
                        received = true;
                    } catch (TimeOutException e) {
                        System.out.println("TM receive TIMEOUT");
                    }
                    // Abort if not all RMs sent PREPARED before ABORT_TIMEOUT
                    if (System.currentTimeMillis() - startTime > ABORT_TIMEOUT) {
                        this.abort();
                        break;
                    }
                } while (!received);

                if (checkCommit()) {
                    this.commit();
                }
            }
        } while (!this.isTerminated());
    }

    /**
     * @TLAAction TMAbort
     */
    private void abort() throws IOException {
        spec.startLog(); // prepare to log event
        // sends Abort to all RM
        for (String rmName : resourceManagers) {
            this.networkManager.send(new Message(this.getName(), rmName, TwoPhaseMessage.Abort.toString(), 0));
        }
        specMessages.add(Map.of("type", TwoPhaseMessage.Abort.toString())); // add Add op for Messages to the trace
        spec.endLog("TMAbort"); // log event
        this.terminate();

        System.out.println("TM sends Abort");
    }

    protected void receive(Message message) throws IOException {
        if (message.getContent().equals(TwoPhaseMessage.Prepared.toString())) {
            String preparedRM = message.getFrom();
            // if the message is from an RM managed by the TM
            if (resourceManagers.contains(preparedRM)) {
                spec.startLog(); // prepare to log event
                this.preparedRMs.add(preparedRM);
                specTmPrepared.add(preparedRM); // add tm state change to the trace
                spec.endLog("TMRcvPrepared"); // log event
            }
        }

        System.out.println(
                "TM received " + message.getContent() + " from " + message.getFrom() + " => " + this.preparedRMs);
    }

    protected boolean checkCommit() {
        // System.out.println("TM check commit (rms = " + this.preparedRMs + ")");
        return this.preparedRMs.size() >= this.resourceManagers.size();
    }

    /**
     * @TLAAction TMCommit
     */
    private void commit() throws IOException {
        spec.startLog(); // prepare to log event
        // sends Commits to all RM
        for (String rmName : resourceManagers) {
            this.networkManager.send(new Message(this.getName(), rmName, TwoPhaseMessage.Commit.toString(), 0));
        }
        specMessages.add(Map.of("type", TwoPhaseMessage.Commit.toString())); // add Add op for Messages to the trace
        spec.endLog("TMCommit"); // log event

        System.out.println("TM sent Commits");

        this.terminate();
    }
}
