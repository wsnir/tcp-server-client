package bgu.spl.net.srv;

public interface Connections<T> {

    void connect(int connectionId, ConnectionHandler<T> handler);

    void send(int connectionId, T msg);

    void disconnect(int connectionId);
}
