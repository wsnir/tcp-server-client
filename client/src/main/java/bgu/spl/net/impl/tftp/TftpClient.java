package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.NoSuchElementException;
import java.util.Scanner;

@SuppressWarnings("CallToPrintStackTrace")
public class TftpClient {
    private BufferedInputStream in;
    private BufferedOutputStream out;
    private final TftpProtocol protocol;
    private final MessageEncoderDecoder<byte[]> encdec;
    private final Socket sock;
    private volatile boolean shouldContinue = true;

    private TftpClient(String host, int port) throws IOException {
        sock = new Socket(host, port);
        encdec = new TftpEncoderDecoder();
        protocol = new TftpProtocol();
    }

    /**
     * The function is responsible for sending the packets from the client to the server 
     *
     * @param msg - the message packet to send
     */
    private synchronized void send(byte[] msg){
        try {
            out.write(encdec.encode(msg));
            out.flush();
        } catch (IOException ex) {ex.printStackTrace();}
    }

    public static void main(String[] args) throws IOException{
        if (args.length != 2) args = new String[]{"localhost", "7777"};
        try {
            TftpClient client = new TftpClient(args[0], Integer.parseInt(args[1]));
            try (Socket sock = client.sock){
                client.in = new BufferedInputStream(sock.getInputStream());
                client.out = new BufferedOutputStream(sock.getOutputStream());
                TftpProtocol protocol = client.protocol;

                // Listening Thread
                Thread listenThread = createListenThread(protocol, client);
                listenThread.start();
                System.out.println("Connected to the server!");

                //Keyboard Thread
                Scanner scanner = new Scanner(System.in);
                while(protocol.shouldContinue() && client.shouldContinue){
                    // Read input from the keyboard
                    try {
                        String userInput = scanner.nextLine();
                        if (userInput.startsWith("DISC")){
                            client.shouldContinue = false;
                        }
                        byte[] message = protocol.keyBoardMessage(userInput);
                        if (message != null){
                            client.send(message);
                        }
                    } catch (NoSuchElementException e) {client.shouldContinue = false;}
                }
                scanner.close();
                try {
                    listenThread.join();
                } catch (InterruptedException ignored) {}
            } catch (IOException ex) {ex.printStackTrace();}
        } catch (ConnectException e) {System.out.println("Server Offline");}
    }

    private static Thread createListenThread(TftpProtocol protocol, TftpClient client) {
        return new Thread(() -> {
            try {
                int read;
                while (protocol.shouldContinue() && (read = client.in.read()) >= 0) {
                    byte[] nextMessage = client.encdec.decodeNextByte((byte) read);
                    if (nextMessage != null) {
                        byte[] msg = protocol.process(nextMessage);
                        if(msg != null){
                            client.send(msg);
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("Server Shutdown");
                System.exit(1);
            }
        });
    }
}

