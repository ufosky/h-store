package edu.brown.hstore.internal;

import java.nio.ByteBuffer;

import org.voltdb.ParameterSet;
import org.voltdb.StoredProcedureInvocation;
import org.voltdb.catalog.Procedure;

import com.google.protobuf.RpcCallback;

public class InitializeTxnMessage extends InternalMessage {

    final ByteBuffer serializedRequest; 
    final Procedure catalog_proc;
    final ParameterSet procParams;
    final RpcCallback<byte[]> clientCallback;
    
    
    public InitializeTxnMessage(ByteBuffer serializedRequest, 
                                 Procedure catalog_proc,
                                 ParameterSet procParams,
                                 RpcCallback<byte[]> clientCallback) {
        
        assert(serializedRequest != null);
        assert(catalog_proc != null);
        assert(procParams != null);
        assert(clientCallback != null);
        
        this.serializedRequest = serializedRequest;
        this.catalog_proc = catalog_proc;
        this.procParams = procParams;
        this.clientCallback = clientCallback;
        
    }
    
    public ByteBuffer getSerializedRequest() {
        return (this.serializedRequest);
    }

    public Procedure getProcedure() {
        return (this.catalog_proc);
    }

    public ParameterSet getProcParams() {
        return (this.procParams);
    }

    public RpcCallback<byte[]> getClientCallback() {
        return (this.clientCallback);
    }
    
    public long getClientHandle() {
        return StoredProcedureInvocation.getClientHandle(this.serializedRequest);
    }
    
    
    
}