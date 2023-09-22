package org.lbee.network;

import org.lbee.protocol.Message;

import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * A message box
 * Messages are taken in unspecified order
 */
public class UnorderedMessageBox implements MessageBox {

    /**
     * Messages of the box
     */
    private final List<Message> messages;

    public UnorderedMessageBox() {
        messages = new LinkedList<>();
    }

    /**
     * Put a message into the box
     * @param message Message to put in the box
     */
    @Override
    public void put(Message message) {
        messages.add(message);
    }

    /**
     * Get a message from the box
     * @return Message get from the box
     */
    @Override
    public Message take() {
        if (messages.isEmpty())
            return null;

        // Take a message in box randomly
        int rndIdx = new Random().nextInt(0, messages.size());
        Message message = messages.get(rndIdx);
        messages.remove(rndIdx);
        return message;
    }
}
