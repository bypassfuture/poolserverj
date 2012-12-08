package com.google.bitcoin.core;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.json.JSONException;

import com.google.bitcoin.bouncycastle.crypto.digests.SHA256Digest;
import com.google.bitcoin.bouncycastle.util.encoders.Hex;
import com.shadworld.jsonrpc.JsonRpcClient;
import com.shadworld.jsonrpc.JsonRpcRequest;
import com.shadworld.jsonrpc.JsonRpcResponse;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.util.RateCalculator;
import com.shadworld.util.Time;
import com.shadworld.utils.L;

public class PSJBlock extends Block{

	public static void main(String[] args) throws ProtocolException, JSONException {
		
		JsonRpcClient client = new JsonRpcClient("http://localhost:8332", "shad", "password");
		JsonRpcRequest request = new JsonRpcRequest("getwork", client.newRequestId());
		JsonRpcResponse response = client.doRequest(request);
		Work work = new Work(null, response.getResult());
		L.println(work.getObject().toString(4));
		
		String data = "00000001c619c5ef02129f4333e8223a9c219a1c753d875b39de61aa0000016f000000001f1e7d2deb34831847d7ef5638e31eddb39e780c18b044e25cdb06b4a6c9e6a74e044c561a13218500000000000000800000000000000000000000000000000000000000000000000000000000000000000000000000000080020000";
		data = work.getData();
		PSJBlock block = new PSJBlock(NetworkParameters.unitTests(), data);
		L.println("hash1:    " + work.getHash1());
		L.println("hash1:    " + block.getMidstateAsHexString());
		//L.println("hash1.1:  " + block.getMidstate1AsHexString());
		L.println("hash1:    " + Utils.bytesToHexString(swapBytes(block.getMidstate(), -1)));
		L.println("hash1:    " + Utils.bytesToHexString(Utils.reverseBytes(swapBytes(block.getMidstate(), -1))));
		L.println("hash1:    " + Utils.bytesToHexString(swapBytes(Utils.reverseBytes(block.getMidstate()), -1)));
		L.println("midstate: " + work.getMidstate());
		L.println("");
		
		block.originalBytes = null;
		byte[] bytes = block.getBytes();
		bytes = swapBytes(bytes, 80);
		byte[] padded = block.padForSHA256(bytes);
		BigInteger blockNum = new BigInteger(padded);
		L.println(data);
		//L.println(blockNum.toString(16));
		L.println(Utils.bytesToHexString(padded));
		L.println(Utils.bytesToHexString(Utils.decodeCompactBits(EASIEST_DIFFICULTY_TARGET).toByteArray()));
		block.solveForTarget(EASIEST_DIFFICULTY_TARGET);
		
	}
	
	/**
	 * For testing, returns header as string suitable for submitting as solution to getwork
	 * @return
	 */
	public String toSolutionString() {
		byte[] padded = padForSHA256(swapBytes(getBytes(), 80));
		return Utils.bytesToHexString(padded);
	}
	
	
	String dataString;
	byte[] originalBytes;
	byte[] noncelessBytes;
	
	public PSJBlock(NetworkParameters params, byte[] bytes) throws ProtocolException {
		super(params, bytes);
		this.bytes = bytes;
	}
	
	/**
	 * use this for header only blocks
	 * @param params
	 * @param data
	 * @throws ProtocolException
	 */
	public PSJBlock(NetworkParameters params, String data) throws ProtocolException {
		super(params, swapBytes(Hex.decode(data), 80));
		this.dataString = data;
	}
	
	private void clearCache() {
		originalBytes = null;
		noncelessBytes = null;
	}
	
	public byte[] getNoncelessBytes() {
		if (noncelessBytes == null) {
			noncelessBytes = Arrays.copyOf(getBytes(), 76);
		}
		return noncelessBytes;
	}
	
	public String getHeaderAsString() {
		StringBuilder sb = new StringBuilder(320);
		byte[] bytes = swapBytes(getBytes(), 80);
		for (int i = 0; i < bytes.length; i++) {
			
			sb.append(Integer.toHexString(bytes[i]));
			//sb.append(Integer.toString(bytes[i], 16));
		}
		return sb.toString();
	}
	
	public byte[] getBytes() {
		if (originalBytes == null) {
			originalBytes = new byte[80];
			
//	        Utils.uint32ToByteStreamLE(version, stream);
//	        stream.write(Utils.reverseBytes(prevBlockHash));
//	        stream.write(Utils.reverseBytes(getMerkleRoot()));
//	        Utils.uint32ToByteStreamLE(time, stream);
//	        Utils.uint32ToByteStreamLE(difficultyTarget, stream);
//	        Utils.uint32ToByteStreamLE(nonce, stream);
			
			Utils.uint32ToByteArrayLE(getVersion(), originalBytes, 0);
			System.arraycopy(Utils.reverseBytes(getPrevBlockHash()), 0, originalBytes, 4, 32);
			System.arraycopy(Utils.reverseBytes(getMerkleRoot()), 0, originalBytes, 36, 32);
			Utils.uint32ToByteArrayLE(getTime(), originalBytes, 68);
			Utils.uint32ToByteArrayLE(getDifficultyTarget(), originalBytes, 72);
			Utils.uint32ToByteArrayLE(getNonce(), originalBytes, 76);	
		}
		return originalBytes;
	}
	
	public byte[] getBytes(long compactDifficultyTarget) {
		if (originalBytes == null) {
			originalBytes = new byte[80];
			
//	        Utils.uint32ToByteStreamLE(version, stream);
//	        stream.write(Utils.reverseBytes(prevBlockHash));
//	        stream.write(Utils.reverseBytes(getMerkleRoot()));
//	        Utils.uint32ToByteStreamLE(time, stream);
//	        Utils.uint32ToByteStreamLE(difficultyTarget, stream);
//	        Utils.uint32ToByteStreamLE(nonce, stream);
			
			Utils.uint32ToByteArrayLE(getVersion(), originalBytes, 0);
			System.arraycopy(Utils.reverseBytes(getPrevBlockHash()), 0, originalBytes, 4, 32);
			System.arraycopy(Utils.reverseBytes(getMerkleRoot()), 0, originalBytes, 36, 32);
			Utils.uint32ToByteArrayLE(getTime(), originalBytes, 68);
			Utils.uint32ToByteArrayLE(compactDifficultyTarget, originalBytes, 72);
			Utils.uint32ToByteArrayLE(getNonce(), originalBytes, 76);	
		}
		return originalBytes;
	}
	
    public long solveForTarget(long compactDifficultyTarget) {
        long tries = 0;
        RateCalculator rate = new RateCalculator(100000, 20);
    	while (true) {
            try {
                // Is our proof of work valid yet?
            	if (Res.isDebug() && tries > 5 && tries % 1000000 == 0) {
            		L.println("Tries: " + tries + " Rate: " + rate.currentRate(Time.SEC));
            	}
            	tries++;
            	if (checkProofOfWork(compactDifficultyTarget, false))  {
            		//L.println("Tries: " + tries);
            		//L.println(" Rate: " + rate.currentRate(Time.SEC));
                	return getNonce();
                }
            	rate.registerEvent();
                // No, so increment the nonce and try again.
                setNonce(getNonce() + 1);
            } catch (VerificationException e) {
                throw new RuntimeException(e);  // Cannot happen.
            }
        }
    }
    
    private byte[] calculateHash(long compactDifficultyTarget) {
    	return Utils.reverseBytes(getBytes(compactDifficultyTarget));
    }
    
    private boolean checkProofOfWork(long compactDifficultyTarget, boolean throwException) throws VerificationException {
        // This part is key - it is what proves the block was as difficult to make as it claims
        // to be. Note however that in the context of this function, the block can claim to be
        // as difficult as it wants to be .... if somebody was able to take control of our network
        // connection and fork us onto a different chain, they could send us valid blocks with
        // ridiculously easy difficulty and this function would accept them.
        //
        // To prevent this attack from being possible, elsewhere we check that the difficultyTarget
        // field is of the right value. This requires us to have the preceeding blocks.
    	//BigInteger target = getDifficultyTargetAsInteger();
    	BigInteger target = Utils.decodeCompactBits(compactDifficultyTarget);
    	if (target.compareTo(BigInteger.valueOf(0)) <= 0 || target.compareTo(params.proofOfWorkLimit) > 0)
            throw new VerificationException("Difficulty target is bad: " + target.toString());
        
    	//BigInteger h = new BigInteger(1, calculateHash(compactDifficultyTarget));
    	BigInteger h = new BigInteger(1, getHash());
        if (h.compareTo(target) > 0) {
            // Proof of work check failed!
            if (throwException)
                throw new VerificationException("Hash is higher than target: " + getHashAsString() + " vs " +
                        target.toString(16));
            else
                return false;
        }
        return true;
    }
	
	public void setNonce(long nonce) {
		clearCache();
		super.setNonce(nonce);
	}
	
	public void setDifficultyTarget(long compactForm) {
		super.setDifficultyTarget(compactForm);
	}
	
    public boolean checkTimestamp() {
        // Allow injection of a fake clock to allow unit testing.
        long currentTime = System.currentTimeMillis() / 1000;
        if (getTime() > currentTime + ALLOWED_TIME_DRIFT)
            return false;
        return true;
    }
    
    /** Returns true if the hash of the block is OK (lower than difficulty target). */
    public boolean checkProofOfWork() throws VerificationException {
        // This part is key - it is what proves the block was as difficult to make as it claims
        // to be. Note however that in the context of this function, the block can claim to be
        // as difficult as it wants to be .... if somebody was able to take control of our network
        // connection and fork us onto a different chain, they could send us valid blocks with
        // ridiculously easy difficulty and this function would accept them.
        //
        // To prevent this attack from being possible, elsewhere we check that the difficultyTarget
        // field is of the right value. This requires us to have the preceeding blocks.
        BigInteger target = getDifficultyTargetAsInteger();

        BigInteger h = new BigInteger(1, getHash());
        if (h.compareTo(target) > 0) {
            // Proof of work check failed!
                return false;
        }
        return true;
    }
    
    public BigInteger getHashAsInteger() {
    	return new BigInteger(1, getHash());
    }
    
    /**
     * Returns the difficulty target as a 256 bit value that can be compared to a SHA-256 hash. Inside a block the
     * target is represented using a compact form. If this form decodes to a value that is out of bounds,
     * an exception is thrown.
     */
    public BigInteger getDifficultyTargetAsInteger(long difficultyTarget) throws VerificationException {
        BigInteger target = Utils.decodeCompactBits(difficultyTarget);
        if (target.compareTo(BigInteger.valueOf(0)) <= 0 || target.compareTo(params.proofOfWorkLimit) > 0)
            throw new VerificationException("Difficulty target is bad: " + target.toString());
        return target;
    }
	
	/**
	 * fixes endianess of bytes returned from decoding a getwork request and also ensures only the first 80 bytes (block header) are parsed.
	 * @param bytes
	 * @return
	 */
	public static byte[] swapBytes(byte[] bytes, int trimLength) {
		byte[] rev = new byte[trimLength != -1 && bytes.length > trimLength ? trimLength : bytes.length];
		for (int i = 0; i < rev.length; i += 4) {
			byte[] chunk = Arrays.copyOfRange(bytes, i ,  i  + 4);
			chunk = Utils.reverseBytes(chunk);
			System.arraycopy(chunk, 0, rev, i , 4);
		}
		return rev;
	}
	
	public static final byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
}

	public static long EASIEST_DIFFICULTY_TARGET() {
		return EASIEST_DIFFICULTY_TARGET;
	}
	
	public byte[] getMidstate() {
		SHA256Digest sha = new SHA256Digest();
		sha.update(getBytes(), 0, 64);
		sha.update(getBytes(), 64, 16);
		byte[] midstate = new byte[32];
		int finalResult = sha.doFinal(midstate, 0);
		return midstate;
	}
	
	public String getMidstateAsHexString() {
		return Utils.bytesToHexString(getMidstate());
	}
	
	public byte[] getMidstate1() {
		MessageDigest digest;
			try {
				digest = MessageDigest.getInstance("SHA-256");
				digest.getProvider();
				digest.update(getBytes(), 0, 64);
		        byte[] midstate = digest.digest();
			return midstate;
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				return null;
			}
	}
	
	public String getMidstate1AsHexString() {
		return Utils.bytesToHexString(getMidstate1());
	}
	
	public byte[] padForSHA256(byte[] bytes) {
		//L.println(Utils.bytesToHexString(bytes));
		BigInteger len = new BigInteger(String.valueOf(bytes.length * 8));
		int mod = (int) (len.longValue() % 512);
		long newLen = mod == 0 ? len.longValue() : len.longValue() - mod + 512;
		int zerosBytes = (440 - mod) /8;
		
		byte[] padded = new byte[(int) (newLen / 8)];
		System.arraycopy(bytes, 0, padded, 0, bytes.length);
//		padded[bytes.length] = 0;
//		padded[bytes.length + 1] = 0;
//		padded[bytes.length + 2] = 0;
		padded[bytes.length + 3] = (byte) 0x80; //0b1000000
//		for (int i = 3; i < zerosBytes; i++) {
//			padded[bytes.length + 1 + i] = 0;
//		}
		byte[] lenBytes = len.toByteArray();
		byte[] lenArray = new byte[8];
		//for (int i = padded.length - 8; i < padded.length - lenBytes.length; i++) {
		//	padded[i] = 0;
		//}
//		for (int i = 0; i < lenArray.length - lenBytes.length; i++)
//			lenArray[i] = 0;
		
		System.arraycopy(lenBytes, 0, lenArray, lenArray.length - lenBytes.length, lenBytes.length);
		lenArray = swapBytes(lenArray, -1);
		System.arraycopy(lenArray, 0, padded, padded.length - 8, 8);
		return padded;
	}
	
}
