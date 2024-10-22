package bgu.spl.net.impl.tftp;

import bgu.spl.net.srv.Server;

public class TftpServer {
    public static void main(String[] args) {
        int port = args.length == 1 ? Integer.parseInt(args[0]) : 7777;
        Server.threadPerClient(
                port,
                TftpProtocol::new, //protocol factory
                TftpEncoderDecoder::new //message encoder decoder factory
        ).serve();
    }
}