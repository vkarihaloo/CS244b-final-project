package edu.stanford.cs244b.chord;

import java.net.InetAddress;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteChordNodeI extends Remote {

    /** Return the identifier of this ChordNode */
    int getShardId() throws RemoteException;
    
    /** Return ip address associated with this ChordNode */
    InetAddress getHost() throws RemoteException;
    
    /** Successor is first entry in the fingerTable */
    RemoteChordNodeI getSuccessor() throws RemoteException;
    
    /** Obtain reference to predecessor */
    RemoteChordNodeI getPredecessor() throws RemoteException;

    /** Set reference to predecessor */
    void setPredecessor(RemoteChordNodeI newPredecessor) throws RemoteException;
    
    /** Ask node to find the successor of the specified identifier */
    public abstract RemoteChordNodeI findSuccessor(int identifier) throws RemoteException;

    /** Contact a series of nodes moving forward around the Chord circle
     *  towards the identifier
     */
    public abstract RemoteChordNodeI findPredecessor(int identifier) throws RemoteException;

    /** Return closest preceding id */
    public abstract RemoteChordNodeI closestPrecedingFinger(int identifier) throws RemoteException;

    /** If ChordNode s is the i'th finger of this ChordNode, update fingerTable */
    public abstract boolean updateFingerTable(ChordNode s, int index) throws RemoteException;
}