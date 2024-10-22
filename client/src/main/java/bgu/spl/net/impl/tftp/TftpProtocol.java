package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessagingProtocol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SuppressWarnings("CallToPrintStackTrace")
public class TftpProtocol implements MessagingProtocol<byte[]>{
// ------------------------- Fields ----------------------------------//
    boolean shouldTerminate = false;
    private FileOutputStream outputStream;
    private FileInputStream inputStream;
    File file;
    private boolean shouldDisc = false;

    /**
     * RRQ function fields
     */
    private boolean rrqCommand = false;
    private String rrqFileName;
    
    /**
     * DIRQ function fields
     */
    private byte[] tmpFileName;
    private String fileNames = null;
    private int stringOffset = 0;
    
    /**
     * WRQ function fields
     */
    private String wrqFileName;
    private boolean wrqCommand = false;
    private byte[] readBuffer;

    /**
     * The function deals with packets that are received by the listening thread
     *
     * @param message - the message sent by the server
     */
    @Override
    public byte[] process(byte[] message) {
        short opCode = TftpEncoderDecoder.bytesToShort(message[0], message[1]);
        return triage(opCode, message);
    }
    
    /**
     * The function deals with creating the right packet according to the command from the keyBoard
     *
     * @param msg - the message sent by the server
     */
    public byte[] keyBoardMessage(String msg){
        String[] words = msg.split(" ");
        if (words.length > 2){
            for (int i = 2; i < words.length; ++i){
                words[1] += " " + words[i];
            }
            words = new String[]{words[0], words[1]};
        }
        return triageKeyBoard(words);
    }

    @Override
    public boolean shouldContinue() {
       return !shouldTerminate;
    }
    
    /**
     * The function is responsible for converting command to the right packet and determines an approach to handle it
     *
     * @param words - the command from the keyBoard
     * @return - "decoded" byte message
     */
    private byte[] triageKeyBoard(String[] words) {
        switch (words[0]) {
            case "LOGRQ":
                return LOGRQ(words);
            case "DELRQ":
                return DELRQ(words);
            case "RRQ":
                return RRQ(words);
            case "WRQ":
                return WRQ(words);
            case "DIRQ":
                return DIRQ();
            case "DISC":
                return DISC();
        }
        return null;
    }

    /**
     * The function is responsible for classifying each packet and determines an approach to handle it
     *
     * @param opCode - the opcode of each message
     * @param message - the message sent by the client
     * @return - "decoded" byte message
     */
    private byte[] triage(short opCode, byte[] message) {
        if (opCode == 3) return DATA(message);
        else if (opCode == 4) return ACK(message);
        else if (opCode == 9) return BCAST(message);
        return ERROR(message);
    }

// functions that are part of the triage section

    /**
     * The function prints all the fields for the dirq command
     */
    private void printDirq(){
        if (fileNames.isEmpty()){
            fileNames = null;
            System.out.println("Queried directory is empty");
            return;
        }
        String[] words = fileNames.split("0");
        for (String word : words) {
            System.out.println(word);
        }
        fileNames = null;
    }

    /**
     * The function is responsible for converting the received file data from byte to string
     *
     * @param msg - The encoded file data
     */
    private void fileToString(byte[] msg){
        for(int i = 6; i < msg.length; i++){
            if (msg[i] != 0 && !(i == msg.length-1 && i < 517)){
                tmpFileName[stringOffset++] = msg[i];
            }
            else {
                if (i == msg.length-1 && i < 517){
                    tmpFileName[stringOffset++] = msg[i];
                }
                String tmp = new String(tmpFileName, 0, stringOffset, StandardCharsets.UTF_8);
                if (fileNames != null){
                    fileNames += "0" + tmp;
                }
                else{
                    fileNames = tmp;
                }
                stringOffset = 0;
            } 
        }
        if (msg.length == 6){
            if (fileNames == null){
                fileNames = "";
            }
            else {
                String tmp = new String(tmpFileName, 0, stringOffset, StandardCharsets.UTF_8);
                fileNames += "0" + tmp;
                stringOffset = 0;
            }
        }
    }

    /**
     * The function is responsible for extracting the necessary information from the data packet
     *
     * @param message - The message sent by the server
     * @return - Decoded byte message (ack)
     */
    private byte[] DATA(byte[] message){
        short blockNum = TftpEncoderDecoder.bytesToShort(message[4],message[5]);
        short dataLength = TftpEncoderDecoder.bytesToShort(message[2], message[3]);
        try {
            //Determines if it's rrq command or dirq
            if(rrqCommand){
                outputStream.write(message, 6, dataLength);
            }
            else{
                fileToString(message);
            }
            // If it was the last data to be sent by the server
            if (dataLength < 512) {
                if(rrqCommand){
                    outputStream.close();
                    System.out.println("RRQ " + rrqFileName + " complete");
                    rrqFileName = null;
                    rrqCommand = false;
                }
                else{
                    printDirq();
                }
            }
        } catch (IOException e) {e.printStackTrace();}
        return sendAck(blockNum);
    }

    /**
     * The function is responsible for displaying the broadCast message correctly to the client
     *
     * @param message - The message sent by the client
     * @return - A null message to fit the scheme
     */
    private byte[] BCAST(byte[] message){
        String str = new String(message, 3, message.length-3, StandardCharsets.UTF_8);
        if((short) message[2] == 1){
            System.out.println("BCAST add " + str);
        }
        else{
            System.out.println("BCAST del " + str);
        } 
        return null;
    }
     
    /**
     * The function is responsible for handling with different ack responds from the server and act accordingly
     *
     * @param message - The message sent by the server
     * @return - Null or data
     */
    private byte[] ACK(byte[] message){
        short blockNum = TftpEncoderDecoder.bytesToShort(message[2], message[3]);
        // Message display to client
        System.out.println("ACK " + String.valueOf(blockNum));
        if (blockNum == 0 && shouldDisc){
            shouldTerminate = true;
        }
        //Dealing with wrq command
        if(blockNum > 0 || wrqCommand){
            if(!wrqCommand){
                System.out.println("WRQ " + wrqFileName + " complete");
                return null;
            }
            WRQInputStream();
            return sendData((short) (blockNum+1));
        }
        return null;
    }
        
    /**
     * The function is responsible for classifying each error packet and display it correctly to the client
     *
     * @param message - The message sent by the client
     * @return - A null message to fit the scheme
     */
    private byte[] ERROR(byte[] message) {
        short errorNum = TftpEncoderDecoder.bytesToShort(message[2], message[3]);
        String str = new String(message, 4, message.length-4, StandardCharsets.UTF_8);
        System.out.println("Error " + String.valueOf(errorNum) + ": " + str);
        if (errorNum == 6 && shouldDisc){
            shouldTerminate = true;
        }
        //specific handling with rrq and dirq commands
        if(errorNum == 1 || errorNum == 6 || errorNum == 7){
            if(rrqCommand){
                try {
                    rrqCommand = false;
                    rrqFileName = null;
                    outputStream.close();
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                } catch (IOException e) {e.printStackTrace();}
            }
            else{
                fileNames = null;
            }
        }
        //specific handling with wrq commands
        else if(errorNum == 5){
            wrqCommand = false;
        }
        return null;
    }

    /**
     * The function is responsible for sending the correct ack packet
     *
     * @param blockNum - The block number of the packet we need to acknowledge
     * @return - A ack response packet
     */
    private byte[] sendAck(int blockNum){
        byte[] returnMessage = new byte[4];
        byte[] blockNumByte = TftpEncoderDecoder.shortToBytes((short) blockNum);
        returnMessage[0] = 0;
        returnMessage[1] = 4;
        returnMessage[2] = blockNumByte[0];
        returnMessage[3] = blockNumByte[1];
        return returnMessage;
    }

    /**
     * The function is part of the creation of a data packet according to what is needed
     *
     * @param blockNum - The block number of the packet we need to acknowledge
     * @return - A data packet according to the requirements
     */
    private byte[] sendData(short blockNum){
        int dataPacketSize = Math.min(readBuffer.length + 6, 518);
        return createDataPacket(blockNum, dataPacketSize);
    }

    /**
     * The function is part of the creation of a data packet according to what is needed
     *
     * @param blockNum - The block number of the packet we need to acknowledge
     * @param dataPacketSize - The size of the data in the packet
     * @return - A data packet according to the requirements
     */
    private byte[] createDataPacket(short blockNum, int dataPacketSize){
        byte[] dataPacket = new byte[dataPacketSize];
        byte[] block = TftpEncoderDecoder.shortToBytes(blockNum);
        byte[] length = TftpEncoderDecoder.shortToBytes((short) (dataPacketSize-6));
        dataPacket[0] = 0;
        dataPacket[1] = 3;
        dataPacket[2] = length[0];
        dataPacket[3] = length[1];
        dataPacket[4] = block[0];
        dataPacket[5] = block[1];
        int offSet = 0;
        for(int i = 6; i < dataPacket.length ; ++i){
            dataPacket[i] = readBuffer[offSet++];
        }
        if(dataPacketSize < 518){
            readBuffer = null;
            wrqCommand = false;
        }
        return dataPacket;
    }

    /**
     * The function is responsible for reading the bytes from the file and store at most 512 bytes each time
     *
     */
    private void WRQInputStream(){
        try {
            int data = -1;
            int counter = 0;
            while(counter < 512 && (data = inputStream.read()) >= 0){
                readBuffer[counter++] = (byte)data;
            }
            if(data < 0){
                inputStream.close();
                readBuffer = Arrays.copyOf(readBuffer, counter);
                inputStream = null;
            }
        } catch (IOException e) {e.printStackTrace();}
    }


// functions that are part of the triageKeyBoard section

    /**
     * The function is responsible for converting the logrg keyboard command into a logrq packet
     *
     * @param words - The command split by spaces
     * @return - A logrq client packet
     */
    private byte[] LOGRQ(String[] words){
        if(words.length != 2 ){
            return null;
        }
        return messageCreate(words, (short) 7);
    }

    /**
     * The function is responsible for converting the Delrq keyboard command into a Delrq packet
     *
     * @param words - The command split by spaces
     * @return - A Delrq client packet
     */
    private byte[] DELRQ(String[] words){
        if(words.length != 2 ){
            return null;
        }
        return messageCreate(words, (short)8);
    }

    /**
     * The function is responsible for converting the Rrq keyboard command into a Rrq packet
     *
     * @param words - The command split by spaces
     * @return - A Rrq client packet
     */
    private byte[] RRQ(String[] words){
        file = new File(words[1]);
        if(file.exists()){
            System.out.println("File already exists");
            return null;
        }
        try {
            outputStream = new FileOutputStream(words[1]);
            rrqFileName = words[1];
            rrqCommand = true;
        } catch (FileNotFoundException e) {e.printStackTrace();}
        return messageCreate(words, (short)1);
    }

    /**
     * The function is responsible for converting the Wrq keyboard command into a Wrq packet
     *
     * @param words - The command split by spaces
     * @return - A Wrq client packet
     */
    private byte[] WRQ(String[] words){
        File file = new File(words[1]);
        if(!file.exists()){
            System.out.println("File does not exist");
            return null;
        }
        try {
            wrqCommand = true;
            wrqFileName = words[1];
            inputStream = new FileInputStream(wrqFileName);
            readBuffer = new byte[512];
        } catch (FileNotFoundException e) {e.printStackTrace();}
        return messageCreate(words, (short)2);
    }

     /**
     * The function is responsible for converting the Dirq keyboard command into a Dirq packet
     *
     * @return - A Dirq client packet
     */
    private byte[] DIRQ(){
        tmpFileName = new byte[512];
        byte[] msgReturn = new byte[2];
        msgReturn[1] = 6;
        return msgReturn;
    }

     /**
     * The function is responsible for converting the Disc keyboard command into a Disc packet
     *
     * @return - A Disc client packet
     */
    private byte[] DISC(){
        shouldDisc = true;
        byte[] msgReturn = new byte[2];
        msgReturn[1] = 10;
        return msgReturn;
    }
    
     /**
     * The function is a template for creating messages with similar scheme
     *
     * @param words - The command split by spaces
     * @param opcode - The created message opcode
     * @return - A client packet according to the requirement
     */
    private byte[] messageCreate(String[] words, short opcode){
        byte[] query = words[1].getBytes();
        short msgSize = (short) (query.length + 3);
        byte[] msgReturn = new byte[msgSize];
        msgReturn[0] = 0;
        msgReturn[1] = (byte) opcode;
        System.arraycopy(query, 0, msgReturn, 2, query.length);
        msgReturn[query.length + 2] = 0;
        return msgReturn;
    }
}