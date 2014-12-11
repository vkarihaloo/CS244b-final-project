package edu.stanford.cs244b.chord;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.stanford.cs244b.Shard;
import edu.stanford.cs244b.Shard.IdentifierAlgorithm;
import edu.stanford.cs244b.Util;

/** Core components of the Chord distributed hash table implementation.
 *  Keeps track of other shards in the ring to ensure O(log n) lookup */

public class ChordNode implements RemoteChordNodeI {
    Registry registry;
    
    final Shard shard;
    
    final static Logger logger = LoggerFactory.getLogger(ChordNode.class);
    
    /** Location of this ChordNode, includes host ip, port, and shardid */
    protected Finger location;
    
    /** Pointer to immediate predecessor, can be used to walk
     *  counterclockwise around the identifier circle */
    protected Finger predecessor;
    
    final static int NUM_FINGERS = 32;
    
    /** In initial Chord implementation, only maintain a pointer to
     *  the direct successor for simplicity.
     *  TODO: keep pointer to all log(n) nodes as required by Chord. */
    protected Finger[] fingerTable;
    
    /** Each file is sent to REPLICATION_FACTOR nodes in
     *  addition to the origin node. */
    final static int REPLICATION_FACTOR = 1;
    
    /** List of successors to check in case of failure */
    protected Finger[] successorList = new Finger[REPLICATION_FACTOR];
    
    protected Stabilizer stabilizer;
        
    public ChordNode(InetAddress host, int port, Shard shard) throws RemoteException {
        super();
        
        this.shard = shard;
        
        this.location = new Finger(host, port);
        fingerTable = new Finger[NUM_FINGERS];
        for (int index=0; index < NUM_FINGERS; index++) {
            fingerTable[index] = location;
        }
        
        for (int i = 0; i < REPLICATION_FACTOR; i++) {
        	successorList[i] = null;
        }
        
        // insane hack to get RMI working in virtualbox
        System.getProperties().put("java.rmi.server.hostname", host.getHostAddress());
        RemoteChordNodeI stub = (RemoteChordNodeI) UnicastRemoteObject.exportObject(this, 0);

        try {
        	registry = LocateRegistry.createRegistry(port);
        } catch (Exception e) {
        	registry = LocateRegistry.getRegistry();
        }
        try {
        	// insert ChordNode into RMI registry
            String rmiURL = location.getRMIUrl();
        	logger.info("Binding to registry at "+rmiURL);
        	Naming.bind(rmiURL, stub);

        } catch (Exception e) {
        	logger.error("Registering host "+host+" in Chord ring with shardId="+shardIdAsHex()+" FAILED");
        	e.printStackTrace();
        }
    }
    
    /** Given a location, lookup the corresponding RemoteChordNodeI */
    public RemoteChordNodeI getChordNode(Finger remoteLocation) throws RemoteException {
        try {
            // OMG, figuring this out was painful...
            // http://euclid.nmu.edu/~rappleto/Classes/RMI/rmi-coding.html
            
            // insane hack to get RMI working in virtualbox
            System.getProperties().put("java.rmi.server.hostname", remoteLocation.host.getHostAddress());
            //Registry registry = LocateRegistry.getRegistry(remoteLocation.host.getHostAddress(), remoteLocation.port);
            String rmiURL = remoteLocation.getRMIUrl();
            
            RemoteChordNodeI chordNode = (RemoteChordNodeI) Naming.lookup(rmiURL);
            Finger nodeLocation = chordNode.getLocation(); // verify that we can contact ChordNode at specified location
            return chordNode;
        } catch (Exception e) {
            logger.error("Failed to get remote ChordNode at location "+remoteLocation, e);
            throw new RemoteException("Failed to get remote ChordNode at location "+remoteLocation);
        }
    }
    
    @Override
    public Finger getLocation() {
        return this.location;
    }
    
    @Override
    public int getShardId() {
        return this.location.shardid;
    }
    
    @Override
    public InetAddress getHost() {
        return this.location.host;
    }
    
    @Override
    public Finger getSuccessor() {
        return fingerTable[0];
    }
    
    @Override
    public Finger getPredecessor() {
        return predecessor;
    }
    
    @Override
    public void setPredecessor(Finger newPredecessor) {
        this.predecessor = newPredecessor;
    }
    
    /** When node <i>n</i> joins the network:
     *  <ol>
     *  <li>Initialize predecessor TODO: and fingers of node <i>n</i></li>
     *  <li>TODO: Update the fingers and predecessors of existing nodes to reflect the addition of node <i>n</i></li>
     *  <li>TODO: Notify higher-level software so it can transfer values associated with keys that node <i>n</i> is now responsible for</li>
     *  </ol>
     *  Returns true if join succeeded, false otherwise
     */
    public boolean join(Finger existingLocation, boolean isFirstNode) {
        logger.info("Joining node "+existingLocation+"; isFirstNode="+isFirstNode);
    	try {
    		predecessor = null;
    		fingerTable[0] = getChordNode(existingLocation).findSuccessor(getShardId()).getLocation();
    	} catch (RemoteException e) {
    		logger.error("Trusted node is unreachable. Failed to join ring. Exiting...");
    		System.exit(1);
    	}
    		
    	try {
    		if (!isFirstNode) {
    			// check for malicious nodes
    			Finger[] remoteFingerTable = getChordNode(getSuccessor()).getFingerTable();
    			
    			// Nodes we must find
    			Set<Integer> nodesToFind = new HashSet<Integer>();
    			for (Finger f : remoteFingerTable) {
    				nodesToFind.add(Integer.valueOf(f.shardid));
    			}
    			// Include trusted node if it isn't in finger table
    			nodesToFind.add(Integer.valueOf(existingLocation.shardid));
    			// Remove node we started from
    			nodesToFind.remove(Integer.valueOf(getSuccessor().shardid));
    			
    			// Walk successor pointers in ring. Stop when you get reach yourself or your successor.
    			// TODO: this will not terminate if the ring has a cycle 
    			Finger successor = remoteFingerTable[0]; // actually the 2nd successor 
    			do {
    				// This also validates that node is reachable
    				Finger next = getChordNode(successor).getSuccessor();
    				
    				// Check if you found a finger you are looking for
    				Integer currId = Integer.valueOf(successor.shardid);
    				if (nodesToFind.contains(currId)) {
    					nodesToFind.remove(currId);
    				}
    				
    				successor = next;
    			} while (successor.shardid != getShardId() && successor.shardid != getSuccessor().shardid);
    			
    			// If not all fingers were found, error occurred
    			// TODO: handle nodes in finger table leaving before they are found
    			if (!nodesToFind.isEmpty()) {
    				logger.error("Not all trusted nodes were found in ring during join. Failed to join ring. Exiting...");
    				System.exit(1);
    			}
    		}
    		
    		refreshSuccessors(0);
    		
    	} catch (RemoteException e) {
    		logger.error("Failed to find successor node while walking ring. Ring is corrupted or contains malicious nodes. Exiting...", e);
    		System.exit(1);
    	}
    	
		stabilizer = new Stabilizer();
		stabilizer.start();
		return true;
    }
    
    /** Periodically run to verify successor relationship */
    public void stabilize() {
    	RemoteChordNodeI successor = null;
    	Finger x = null;
    	try {
    		successor = getChordNode(getSuccessor());
    		x = successor.getPredecessor();
    	} catch (RemoteException e) {
    		updateSuccessor();
    	}
    	
		if (x != null && Util.withinInterval(x.shardid, location.shardid+1, getSuccessor().shardid-1)) {
			logger.info("Updating successor from "+Integer.toHexString(getSuccessor().shardid)+" to "+Integer.toHexString(x.shardid));
		    fingerTable[0] = x;
		    try {
		    	// Tell precedessors to refresh successor list
		    	getChordNode(predecessor).refreshSuccessors(REPLICATION_FACTOR - 1);
		    } catch (RemoteException e) {
		    	logger.error("Failed to notify predecessor of changed successor");
		    }
		}
		
		try {
			successor.notifyPredecessor(location);
		} catch (RemoteException e) {
			updateSuccessor();
		}
    }
    
    /** Notify node of request to become predecessor */
    @Override
    public void notifyPredecessor(Finger newPredecessor) {
    	if (predecessor == null ||
    	        Util.withinInterval(newPredecessor.shardid, predecessor.shardid+1, location.shardid-1)) {
    	    String oldPredecessor = (predecessor == null ? "null" : Integer.toHexString(predecessor.shardid));
    	    logger.info("Updating predecessor from "+oldPredecessor+" to "+Integer.toHexString(newPredecessor.shardid));
    		predecessor = newPredecessor;
    	}
    }
    
    /** Choose a random node and update finger table */
    public void fixFingers() {
    	try {
	    	Random rgen = new Random();
	    	int i = rgen.nextInt(NUM_FINGERS - 1) + 1; // TODO: use power of 2 here
	    	Finger f = findSuccessor(fingerTable[i].shardid).getLocation();
//	    	logger.info("Updating fingerTable[" + i + "] from "+ fingerTable[i] + " to " + f);
	    	fingerTable[i] = f;
    	} catch (RemoteException e) {
    		logger.error("Failed to update finger table", e);
    	}
    }
    
    @Override
    public RemoteChordNodeI findSuccessor(int identifier) throws RemoteException {
        RemoteChordNodeI next = findPredecessor(identifier);
        return getChordNode(next.getSuccessor());
    }
    
    @Override
    public RemoteChordNodeI findPredecessor(int identifier) throws RemoteException {
        RemoteChordNodeI next = this;
        //logger.info("FindPredecessor for id="+Integer.toHexString(identifier)+" next_shardid="+Integer.toHexString(next.getShardId()));
        while (!Util.withinInterval(identifier, next.getShardId()+1, next.getSuccessor().shardid)) {
            next = next.closestPrecedingFinger(identifier);
//            logger.info("FindPredecessor for id="+Integer.toHexString(identifier)+" next_shardid="+Integer.toHexString(next.getShardId()));
        }
        return next;
        
    }
    
    @Override
    public RemoteChordNodeI closestPrecedingFinger(int identifier) {
        
        // lookup in finger tree 
        for (int index = NUM_FINGERS-1; index >= 0; index--) {
            try {
                if (Util.withinInterval(fingerTable[index].shardid, location.shardid+1, identifier-1)) {
                    return getChordNode(fingerTable[index]);
                }
            } catch (RemoteException e) {
                logger.error("closestPrecedingFinger failed to lookup finger", e);
                // TODO: howto deal with lookup failure until fixFingers runs?
            }
        }
        return this;
    }
    
    /** Leave Chord ring and update other nodes */
    public void leave(int exitCode) {
    	if (this.location.host == predecessor.host) {
    		return;
    	}
    	
    	stabilizer.cancel();
    	
    	try {
    		getChordNode(getSuccessor()).setPredecessor(predecessor);
    	} catch (RemoteException e) {
    		logger.error("Failed to set successor's predecessor", e);
    	}
    	
    	for (int i = 0; i < NUM_FINGERS; i++) {
    		int fingerValue = (location.shardid - (1 << i)) + 1;
    		
    		try {
    			RemoteChordNodeI p = findPredecessor(fingerValue);
    			p.removeNode(this, i, getSuccessor());
    		} catch (RemoteException e) {
    			logger.error("Failed to notify predecessor or node leaving", e);
    		}
    		
    	}
    	
    	System.exit(exitCode);
    }
    
    @Override
    public void removeNode(ChordNode node, int i, Finger replacement) {
    	if (fingerTable[i].host == node.getLocation().host) {
    		fingerTable[i] = replacement;
    		try {
    			getChordNode(predecessor).removeNode(node, i, replacement);
    		} catch (RemoteException e) {
    			logger.error("Failed to remove node from predecessor", e);
    		}
    	}
    }
    
    /** Convenience method for displaying shardid as a hex string */
    public String shardIdAsHex() {
        return Integer.toHexString(location.shardid);
    }
    
    @Override
    public String toString() {
        return location.toString();
    }
    
    public boolean ownsIdentifier(int identifier) {
    	return Util.withinInterval(identifier, this.getShardId(), this.getSuccessor().shardid-1);
    }
		
	/** Look up file on remote node */
	public byte[] forwardLookup(int identifier, String hash) {
	    //for (int index = 0; index < REPLICATION_FACTOR; index++) {
		try {
			RemoteChordNodeI node = getChordNode(findPredecessor(identifier).getLocation());
			return node.getFile(hash);
		} catch (RemoteException e) {
			logger.error("Error looking up file on remote node", e);
			return null;
		}
	    //}
	}

	/** Remote method to return item if contained on this server */
	@Override
	public byte[] getFile(String hash) {
		logger.info("Looking up object for remote server");
		try {
			return this.shard.getItemAsByteArray(hash);
		} catch (Exception e) {
			logger.error("Error getting file", e);
			return null;
		}
	}
	
	/** Get finger table for new node to verify */
	@Override
	public Finger[] getFingerTable() {
		return fingerTable;
	}
	
	/** Find node where we should start replication from, and send saved file down the ring to be replicated */
    public void beginReplicatingFile(int identifier, byte[] data) {
        try {
            if (REPLICATION_FACTOR > 0) {
                getChordNode(findPredecessor(identifier).getLocation()).replicateFile(data, REPLICATION_FACTOR);
            }
        } catch (RemoteException e) {
            logger.error("Failed to replicate file", e);
        }
    }
	
	/** Receive replication request from predecessor */
	@Override
	public void replicateFile(byte[] data, int nodesLeft) {
		InputStream uploadInputStream = new ByteArrayInputStream(data);
		
		try {
		    // always use SHA256_REPLICATE for replicated files, since
            // only the user's node has secret key for HMAC
			shard.saveFile(uploadInputStream, IdentifierAlgorithm.SHA256_REPLICATE);
		} catch (Exception e) {
			logger.error("Failed to save replicated file", e);
		}
		
		if (nodesLeft > 0) {
			try {
				getChordNode(getSuccessor()).replicateFile(data, nodesLeft - 1);
			} catch (RemoteException e) {
				logger.error("Failed to replicate file further", e);
			}
		}
	}

	
	/** Used to update successor list */
	@Override
	public void refreshSuccessors(int nodesLeft) throws RemoteException {
		Set<Integer> seenSuccessors = new HashSet<Integer>();
		Finger successor = getSuccessor();
		for (int i = 0; i < REPLICATION_FACTOR; i++) {
			if (seenSuccessors.contains(Integer.valueOf(successor.shardid))) {
				// End of ring, set rest of list to null
				while (i < REPLICATION_FACTOR) {
					successorList[i] = null;
					i++;
				}
				break;
			}
			successorList[i] = successor;
			seenSuccessors.add(Integer.valueOf(successor.shardid));
			successor = getChordNode(successor).getSuccessor();
		}
		
		if (nodesLeft > 0) {
			getChordNode(predecessor).refreshSuccessors(nodesLeft - 1);
		}
	}
	
	/** Indicates whether successor pointers are correct */
	public boolean stable() {
		return getSuccessor().shardid != location.shardid;
	}
	
	/** Try successor list if successor is unreachable */
	public void updateSuccessor() {
		boolean success = false;
		
		for (int i = 1; i < REPLICATION_FACTOR; i++) {
			fingerTable[0] = successorList[i];
			try {
				// Update successor list using new direct successor
				refreshSuccessors(0);
				success = true;
				break;
			} catch (RemoteException e) {
				logger.error("Unreachable successor in successorList", e);
			}
		}
		
		if (success) {
			logger.info("Successfully recovered from successor failure");
		} else {
			logger.error("All successors are unreachable. Exiting...");
			leave(1);
		}
	}
    
    public class Stabilizer extends Thread {
        final static int SLEEP_MILLIS = 1000;
        
        /** Run stabilization and fix fingers for ChordNode */
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    stabilize();
                    fixFingers();
//                    logger.info("Node "+Integer.toHexString(location.shardid)+" predecessor="+Integer.toHexString(predecessor.shardid)+" successor="+Integer.toHexString(fingerTable[0].shardid));
                    Thread.sleep(SLEEP_MILLIS);
                }
            } catch (InterruptedException e) {
                logger.info("Stabilizer exiting...", e);
            }
        }
        
        /** Kill stabilization thread */
        public void cancel() {
            interrupt();
        }
    }
}
