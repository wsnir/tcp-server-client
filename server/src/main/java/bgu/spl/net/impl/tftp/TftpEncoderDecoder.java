package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.util.Arrays;

public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {
    private byte[] bytes = new byte[518];
    private int pSize = 6;
    private int len = 0;
    private short opCode;

    /**
     * The function decodes the message
     *
     * @param nextByte - Next byte to decode
     * @return - A completed decoded message or null if not ready yet
     */
    @Override
    public byte[] decodeNextByte(byte nextByte) {
        pushByte(nextByte);
        if (len == 2){
            opCode = bytesToShort(bytes[0], bytes[1]);
            if (opCode == 6 || opCode > 8  || opCode < 1){
                return reSize(len);
            }
        }
        if (len > 2){
            if (opCode == 3) return DATA();
            if (opCode == 4) return ACK();
            return zeroTerm(nextByte);
        }
        return null;
    }

    /**
     * The function encodes the message
     *
     * @param message - The client message to encode
     * @return - A completed encoded message 
     */
    @Override
    public byte[] encode(byte[] message) {
        return message;
    }

    /**
     * The function adds bytes to the message we decode
     *
     * @param nextByte - Next byte add to the message in process
     */
    private void pushByte(byte nextByte) {
        if (len >= bytes.length){
            bytes = Arrays.copyOf(bytes, len*2);
        }
        bytes[len++] = nextByte;
    }

    /**
     * The function resizes the array to the needed size
     *
     * @param length - The desirable size
     * @return - A resized array
     */
    private byte[] reSize(int length){
        len = 0;
        return Arrays.copyOf(bytes, length);
    }

    /**
     * The function decodes the message
     *
     * @param first - first byte
     * @param second - second byte
     * @return - the concatenation of bytes as a short
     */
    protected static short bytesToShort(byte first, byte second) {
        return (short) (((short) first) << 8 | ((short) second) & 0x00ff);
    }

     /**
     * The function converts shorts to byte
     *
     * @param num - short to convert
     * @return - the num in bytes
     */
    protected static byte[] shortToBytes(short num) {
        return new byte[]{(byte) (num >> 8), (byte) (num & 0xff)};
    }

     /**
     * The function converts byte to shorts 
     *
     * @param currByte - byte to convert
     * @return - the byte in short
     */
    private byte[] zeroTerm(byte currByte) {
        if (currByte == 0){
            return reSize(len - 1);
        }
        return null;
    }

    /**
     * The function takes part in decoding a data message
     *
     * @return - decoded data packet
     */
    private byte[] DATA() {
        if (len == 4){
            pSize = bytesToShort(bytes[2], bytes[3]) + 6;
        }
        if (len == pSize){
            return reSize(len);
        }
        return null;
    }

    /**
     * The function takes part in decoding an ack message
     *
     * @return - decoded ack packet
     */
    private byte[] ACK() {
        if (len == 4){
            return reSize(len);
        }
        return null;
    }
}