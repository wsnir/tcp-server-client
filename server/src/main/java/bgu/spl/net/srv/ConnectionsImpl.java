package bgu.spl.net.srv;

import java.util.concurrent.ConcurrentHashMap;

public class ConnectionsImpl<T> implements Connections<T> {
    
    private final ConcurrentHashMap<Integer, ConnectionHandler<T>> connections = new ConcurrentHashMap<>();

    @Override
    public void connect(int connectionId, ConnectionHandler<T> handler){
        connections.put(connectionId, handler);
    }

    @Override
    public void send(int connectionId, T msg){
        ConnectionHandler<T> handler =  connections.get(connectionId);
        if (handler == null) return;
        handler.send(msg);
    }

    @Override
    public void disconnect(int connectionId){
        connections.remove(connectionId);
    }

}
