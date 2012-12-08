package com.shadworld.poolserver.source.merged;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.bitcoin.core.NetworkParameters;
import com.google.bitcoin.core.PSJBlock;
import com.google.bitcoin.core.Utils;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.utils.L;

public class MergedMiningGroup {

	ParentDaemon parent;
	List<AuxDaemon> auxs;
	
	public static void main(String[] args) throws Exception {
		JsonRpcClient clientp = new JsonRpcClient("http://localhost:8362", "rpcuser", "rpcpassword");
		JsonRpcClient clientaux = new JsonRpcClient("http://localhost:8352", "rpcuser", "rpcpassword");
		
		JsonRpcRequest request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETAUXBLOCK, 1);
		
		JsonRpcResponse response = clientp.doRequest(request);
		JSONObject auxBlockParent = response.getResult();
		auxBlockParent.put("chainIndex", calcChainIndex(auxBlockParent.getInt("chainid"), 2, 0));
		L.println("Parent: " + auxBlockParent.toString(4));
		response = clientaux.doRequest(request);
		JSONObject auxBlockAux = response.getResult();
		auxBlockAux.put("chainIndex", calcChainIndex(auxBlockAux.getInt("chainid"), 2, 0));
		L.println("Aux: " + auxBlockAux.toString(4));
		
		String firstHash;
		String secondHash;
		if (auxBlockParent.getInt("chainIndex") == 0) {
			firstHash = auxBlockParent.getString("hash");
			secondHash = auxBlockAux.getString("hash");
		} else {
			firstHash = auxBlockAux.getString("hash");
			secondHash = auxBlockParent.getString("hash");
		}
		
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_BUILDMERKLETREE, 1, firstHash, secondHash);
		//request = new JsonRpcRequest(JsonRpcRequest.METHOD_BUILDMERKLETREE, 1, firstHash);
		JSONArray merkleTree = clientp.doRequest(request).getJSONObject().getJSONArray("result");
		L.println("MerkleTree: " + merkleTree.toString(4));
		
		String aux = merkleTree.getString(2) + "0000000300000000";
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORKAUX, 1, aux);
		JSONObject work = clientp.doRequest(request).getResult();
		L.println("Work: " + work.toString(4));
		
		int parentChainIndex = auxBlockParent.getInt("chainIndex");
		int auxChainIndex = auxBlockAux.getInt("chainIndex");
		
		
		String data = work.getString("data");
		PSJBlock block = new PSJBlock(NetworkParameters.prodNet(), data);
		L.println("our hash: " + block.getHashAsString());
		L.println("hdr merkle_root: " + data.substring(72,136));
		
		PSJBlock tempBlock = new PSJBlock(NetworkParameters.prodNet(), data.replace(data.substring(72,136), merkleTree.getString(parentChainIndex)));
		L.println("parent merkle hash: " + Utils.bytesToHexString(Utils.doubleDigest(PSJBlock.swapBytes(tempBlock.getMerkleRoot(),32))));
		tempBlock = new PSJBlock(NetworkParameters.prodNet(), data.replace(data.substring(72,136), merkleTree.getString(auxChainIndex)));
		L.println("aux merkle hash:    " + Utils.bytesToHexString(Utils.doubleDigest(PSJBlock.swapBytes(tempBlock.getMerkleRoot(),32))));
		
		
		//get AuxPow
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORKAUX, 1, "", data);
		response = clientp.doRequest(request);
		L.println("?: " + response.getResult().toString(4));
		
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORKAUX, 1, "", data, parentChainIndex, merkleTree.get(parentChainIndex));
		response = clientp.doRequest(request);
		L.println("proof par: " + response.getResult().toString(4));
		
		String auxPoW = response.getResult().getString("auxpow");
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETAUXBLOCK, 1, merkleTree.get(parentChainIndex), auxPoW);
		response = clientp.doRequest(request);
		L.println("result par: " + response.getJSONObject().getBoolean("result"));
		
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETWORKAUX, 1, "", data, auxChainIndex, merkleTree.get(auxChainIndex));
		response = clientp.doRequest(request);
		L.println("proof aux: " + response.getResult().toString(4));
		
		auxPoW = response.getResult().getString("auxpow");
		request = new JsonRpcRequest(JsonRpcRequest.METHOD_GETAUXBLOCK, 1, merkleTree.get(auxChainIndex), auxPoW);
		response = clientaux.doRequest(request);
		L.println("result aux: " + response.getJSONObject().getBoolean("result"));
		
		
		clientp.getClient().stop();
		clientaux.getClient().stop();
	}
	
	public static int calcChainIndex(int chainId, int numBranches, long nonce) {
	    long rand = nonce * 1103515245 + 12345;
	    rand += chainId;
	    rand = rand * 1103515245 + 12345;
	    return (int) (rand % numBranches);
	}
	
}
