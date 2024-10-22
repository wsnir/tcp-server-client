package bgu.spl.net.impl.tftp;

import bgu.spl.net.api.MessageEncoderDecoder;
import java.util.Arrays;

public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {
    private byte[] bytes = new byte[518];
    private int pSize = 6;
    private int len = 0;
    private short opCode;

    @Override
    public byte[] decodeNextByte(byte nextByte) {
        pushByte(nextByte);
        if (len == 2){
            opCode = bytesToShort(bytes[0], bytes[1]);
        }
        if (len > 2){
            if (opCode == 3) return DATA();
            if (opCode == 4) return ACK();
            if (opCode == 5) return ERROR(nextByte);
            return BCAST(nextByte);
        }
        return null;
    }

    @Override
    public byte[] encode(byte[] message) {
        return message;
    }

    private void pushByte(byte nextByte) {
        if (len >= bytes.length){
            bytes = Arrays.copyOf(bytes, len*2);
        }
        bytes[len++] = nextByte;
    }

    private byte[] reSize(int length){
        len = 0;
        return Arrays.copyOf(bytes, length);
    }

    protected static short bytesToShort(byte first, byte second) {
        return (short) (((short) first) << 8 | ((short) second) & 0x00ff);
    }

    protected static byte[] shortToBytes(short num) {
        return new byte[]{(byte) (num >> 8), (byte) (num & 0xff)};
    }

    private byte[] zeroTerm(byte currByte) {
        if (currByte == 0){
            return reSize(len - 1);
        }
        return null;
    }

    private byte[] ERROR(byte currByte) {
        if (len > 4){
            return zeroTerm(currByte);
        }
        return null;
    }

    private byte[] BCAST(byte currByte) {
        if (len > 3){
            return zeroTerm(currByte);
        }
        return null;
    }

    private byte[] DATA() {
        if (len == 4){
            pSize = bytesToShort(bytes[2], bytes[3]) + 6;
        }
        if (len == pSize){
            return reSize(len);
        }
        return null;
    }

    private byte[] ACK() {
        if (len == 4){
            return reSize(len);
        }
        return null;
    }

}