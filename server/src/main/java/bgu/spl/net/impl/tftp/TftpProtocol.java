package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.Connections;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

class holder {
    static ConcurrentHashMap<Integer, AtomicReference<String>> idsAndNames = new ConcurrentHashMap<>();
}
/**
 * This class determines the protocol of our server
 */
@SuppressWarnings("CallToPrintStackTrace")
public class TftpProtocol implements BidiMessagingProtocol<byte[]>  {
// ------------------------- Fields ----------------------------------//
    private boolean shouldTerminate = false;
    private int connectionId;
    private final AtomicReference<String> name = new AtomicReference<>();
    private Connections<byte[]> connections;
    private boolean loggedIn = false;

    /**
     * For rrq, wrq, delrq saves the file we work on
     */
    private String file;
    private byte[] readBuffer = null;
    private final String relativeFilesPath = "Files/";

    /**
     * WRQ function fields
     */
    private FileOutputStream outputStream;

    /**
     * DIRQ function fields
     */
    private String[] fileNames;
    private int dirqIndex;
    private int nameIndex;
    private final File directory = new File(relativeFilesPath);

    /**
     * RRQ function fields
     */
    private FileInputStream inputStream;
    private boolean rrqCommand = false;

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
        holder.idsAndNames.put(connectionId, name);
    }

    @Override
    public void process(byte[] message) {
        short opCode = TftpEncoderDecoder.bytesToShort(message[0], message[1]);
        triage(opCode, message);
    }

    @Override
    public boolean shouldTerminate() {
        if (shouldTerminate){
            connections.disconnect(connectionId);
            holder.idsAndNames.remove(connectionId);
        }
        return shouldTerminate;
    }

    /**
     * A synchronized version of connections.send()
     */
    private void syncSend(int connectionId, byte[] msg){
        synchronized (name){
            connections.send(connectionId, msg);
        }
    }

    /**
     * The function is responsible for classifying each packet and determines an approach to handle it
     *
     * @param opCode - the opcode of each message
     * @param message - the message sent by the client
     */
    private void triage(short opCode, byte[] message) {
        if (opCode == 3) DATA(message);
        else if (opCode == 4) ACK(message);
        else if (opCode == 1) RRQ(message);
        else if (opCode == 2) WRQ(message);
        else if (opCode == 8) DELRQ(message);
        else if (opCode == 6) DIRQ();
        else if (opCode == 7) LOGRQ(message);
        else if (opCode ==10) DISC();
        else ERROR(4);
    }

    /**
     * The function is responsible for the RRQ packets that are received
     *
     * @param message - the message sent by the client
     */
    private void RRQ(byte[] message) {
        file = new String(message, 2, message.length-2, StandardCharsets.UTF_8);
        String filePath = relativeFilesPath + file;
        File fileRrq = new File(filePath);
        if (!fileRrq.exists()){
            ERROR((short) 1);
        }
        else if (!loggedIn){
            ERROR((short) 6);
        }
        else {
            try {
                rrqCommand = true;
                readBuffer = new byte[512];
                inputStream = new FileInputStream(filePath);
                RRQInputStream();
                sendData(1);
            } catch (FileNotFoundException e) {ERROR((short) 1);}
        }
    }

    /**
     * The function is responsible for the WRQ packet that are received
     *
     * @param message - the message sent by the client
     */
    private void WRQ(byte[] message){
        file = new String(message, 2, message.length-2, StandardCharsets.UTF_8);
        String filePath = relativeFilesPath + file;
        File fileWrq = new File(filePath);
        // Makes sure that the file doesn't already exist
        if (fileWrq.exists()){
            ERROR((short) 5);
        }
        else if (!loggedIn){
            ERROR((short) 6);
        }
        else {
            try {
                outputStream = new FileOutputStream(file);
                syncSend(connectionId, sendAck(0));
            } catch (FileNotFoundException e) {e.printStackTrace();}
        }
    }

    /**
     * The function is responsible for the DATA packet that are received
     *
     * @param message - the message sent by the client
     */
    private void DATA(byte[] message) {
        if (!loggedIn){
            ERROR(6);
            return;
        }
        short blockNum = TftpEncoderDecoder.bytesToShort(message[4],message[5]);
        short dataLength = TftpEncoderDecoder.bytesToShort(message[2], message[3]);
        try {
            // Receive file data in chunks and write to file
            outputStream.write(message, 6, dataLength);
            syncSend(connectionId, sendAck(blockNum));
            if (dataLength < 512) {
                outputStream.close();
                Path sourcePath = Paths.get(file);
                Path destinationDirectory = Paths.get(relativeFilesPath + file);
                Files.move(sourcePath, destinationDirectory, StandardCopyOption.ATOMIC_MOVE);
                broadcast((byte) 1, file.getBytes());
                file = "";
            }
        } catch (IOException e) {e.printStackTrace();}
    }

    /**
     * The function is responsible for the ACK packet that is received
     *
     * @param message - the message sent by the client
     */
    private void ACK(byte[] message){
        if (!loggedIn){
            ERROR(6);
        }
        else {
            short blockNum = TftpEncoderDecoder.bytesToShort(message[2], message[3]);
            if(readBuffer == null){
                return;
            }
            if(rrqCommand){
                RRQInputStream();
            }
            else {
                dirqProcess();
            }
            sendData(blockNum + 1);
        }
    }

    /**
     * The function is responsible for the DIRQ packet that is received
     *
     */
    private void DIRQ() {
        if (!loggedIn){
            ERROR(6);
            return;
        }
        fileNames = directory.list();
        assert fileNames != null;
        if (fileNames.length == 0){
            readBuffer = new byte[0];
        }
        else{
            readBuffer = new byte[512];
        }
        dirqIndex = 0;
        nameIndex = 0;
        dirqProcess();
        sendData(1);
    }

    private void dirqProcess(){
        int counter = 0;
        if (fileNames == null){
            readBuffer = Arrays.copyOf(readBuffer, counter);
            return;
        }
        if (nameIndex < 0){
            readBuffer[counter++] = 0;
            nameIndex = 0;
        }
        while (dirqIndex < fileNames.length && counter < readBuffer.length){
            byte[] currName = fileNames[dirqIndex].getBytes();
            while (nameIndex < currName.length && counter < readBuffer.length){
                readBuffer[counter++] = currName[nameIndex++];
            }
            if (nameIndex == currName.length){
                ++dirqIndex;
                if (dirqIndex == fileNames.length){
                    readBuffer = Arrays.copyOf(readBuffer, counter);
                    fileNames = null;
                    break;
                }
                else {
                    if (counter < readBuffer.length){
                        readBuffer[counter++] = 0;
                        nameIndex = 0;
                    }
                    else {
                        nameIndex = -1;
                    }
                }
            }
        }
    }

    /**
     * The function is responsible for the LOGRQ packet that are received
     *
     * @param message - the message sent by the client
     */
    private void LOGRQ(byte[] message) {
        // client itself (socket wise) is logged in
        if (loggedIn){
            ERROR(0);
            return;
        }
        String userName = new String(message, 2, message.length-2, StandardCharsets.UTF_8);
        boolean available = true;
        // searches for identical names
        for (AtomicReference<String> currName : holder.idsAndNames.values()){
            if (userName.equals(currName.get())){
                available = false;
                break;
            }
        }
        if (available){
            synchronized(name){
                name.set(userName);
                loggedIn = true;
                syncSend(connectionId, sendAck(0));
            }
        }
        // username is taken
        else {
            ERROR(7);
        }
    }

    /**
     * The function is responsible for the DELRQ packet that are received
     *
     * @param message - the message sent by the client
     */
    private void DELRQ(byte[] message) {
        file = new String(message, 2, message.length-2, StandardCharsets.UTF_8);
        File fileToDelete = new File(relativeFilesPath +  file);
        // Check if the file exists
        if (!fileToDelete.exists()){
            ERROR(1);
        }
        else if (!loggedIn){
            ERROR(6);
        }
        else {
            // Attempt to delete the file
            boolean deleted = fileToDelete.delete();
            while (fileToDelete.exists()){
                try {
                    //noinspection BusyWait
                    Thread.sleep(250);
                    deleted = fileToDelete.delete();
                } catch (Exception ignored) {}
            }
            if (deleted){
                syncSend(connectionId, sendAck(0));
                broadcast((byte) 0, file.getBytes());
            }
            else {
                ERROR(1);
            }
        }
        file = "";
    }

    /**
     * The function is responsible for the DISC packet that are received
     */
    private void DISC() {
        shouldTerminate = true;
        if (!loggedIn){
            ERROR(6);
        }
        else {
            syncSend(connectionId, sendAck(0));
        }
    }

    /**
     * The function is responsible for the ERROR packet that are received
     *
     * @param errorCode - the type of error we need to send
     */
    private void ERROR(int errorCode) {
        String errMsg = "";
        if (errorCode == 1) errMsg = "File not found";
        else if (errorCode == 5) errMsg = "File already exists";
        else if (errorCode == 6) errMsg = "User not logged in";
        else if (errorCode == 7) errMsg = "User already logged in";
        else if (errorCode == 0) errMsg = "Client already logged in";
        else if (errorCode == 4) errMsg = "Illegal TFTP operation";
        byte[] msgInBytes = errMsg.getBytes();
        byte[] errInBytes = TftpEncoderDecoder.shortToBytes((short) errorCode);
        byte[] errPacket = new byte[msgInBytes.length + 5];
        errPacket[1] = 5; errPacket[2] = errInBytes[0]; errPacket[3] = errInBytes[1];
        System.arraycopy(msgInBytes, 0, errPacket, 4, msgInBytes.length);
        syncSend(connectionId, errPacket);
    }

    /**
     * The function is responsible for handling with the broadcast
     *
     * @param delOrAdd - If a file was added or deleted
     * @param fileName - the name of the file we need to inform about
     */
    private void broadcast(byte delOrAdd, byte[] fileName) {
        byte[] castPacket = new byte[fileName.length + 4];
        castPacket[1] = 9; castPacket[2] = delOrAdd;
        System.arraycopy(fileName, 0, castPacket, 3, fileName.length);
        holder.idsAndNames.forEach((Integer id, AtomicReference<String> userName) -> {
            if (userName.get() != null){
                //noinspection SynchronizationOnLocalVariableOrMethodParameter
                synchronized(userName) {
                    connections.send(id, castPacket);
                }
            }
        });
    }

    /**
     * The function is responsible for reading the file in bytes and storing it for comfortable access
     */
    private void RRQInputStream(){
        try {
            int data = -1;
            int counter = 0;
            while(counter < readBuffer.length && (data = inputStream.read()) >= 0){
                readBuffer[counter++] = (byte) data;
            }
            if (data < 0){
                inputStream.close();
                readBuffer = Arrays.copyOf(readBuffer, counter);
                inputStream = null;
            }
        } catch (IOException e) {e.printStackTrace();}
    }

    /**
     * The function is responsible for sending the correct data packet in a rrq command
     *
     * @param blockNum - the number of the block we need to send
     */
    private void sendData(int blockNum){
        int dataPacketSize = Math.min(readBuffer.length + 6, 518);
        syncSend(connectionId, createDataPacket((short) blockNum, dataPacketSize));
    }

    /**
     * The function is responsible for creating the correct data packet in a rrq command
     *
     * @param blockNum - the number of the block we need to send
     * @param dataPacketSize - the size of the current packet
     */
    private byte[] createDataPacket(short blockNum, int dataPacketSize){
        byte[] dataPacket = new byte[dataPacketSize];
        byte[] block = TftpEncoderDecoder.shortToBytes(blockNum);
        byte[] length = TftpEncoderDecoder.shortToBytes((short) (dataPacketSize-6));
        dataPacket[1] = 3;
        dataPacket[2] = length[0];
        dataPacket[3] = length[1];
        dataPacket[4] = block[0];
        dataPacket[5] = block[1];
        int offSet = 0;
        for(int i = 6; i < dataPacket.length ; ++i){
            dataPacket[i] = readBuffer[offSet++];
        }
        if (dataPacketSize < 518){
            rrqCommand = false;
            readBuffer = null;
            file = "";
        }
        return dataPacket;
    }

     /**
     * The function is responsible for sending ack message
     *
     * @param blockNum - the number of the block we need to send
     */
    private byte[] sendAck(int blockNum){
        byte[] returnMessage = new byte[4];
        byte[] blockNumByte = TftpEncoderDecoder.shortToBytes((short) blockNum);
        returnMessage[1] = 4;
        returnMessage[2] = blockNumByte[0];
        returnMessage[3] = blockNumByte[1];
        return returnMessage;
    }
}

