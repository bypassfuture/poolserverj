package com.shadworld.poolserver.entity;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.json.JSONException;
import org.json.JSONObject;

import com.shadworld.poolserver.conf.Res;

public class Work implements Serializable {

	private String jsonString;
	private transient JSONObject object;
	
	//private long difficultyTarget;  // "nBits"
	private String midstate;
	private String data;
	private UniquePortionString dataUniquePortion;
	private String hash1;
	private String target;
	
	public Work() {
	}
	
	public Work(String jsonString, JSONObject object) {
		this.jsonString = jsonString;
		this.object = object;
	}
	
	private void writeObject(ObjectOutputStream out) throws IOException {
		fillFields();
		out.defaultWriteObject();
	}
	
	private void fillFields() {
		getMidstate();
		getData();
		getDataUniquePortion();
		getHash1();
		getTarget();
	}
	
	private boolean setField(final String key, final String value) {
		
		if (getObject() != null) {
			jsonString = null;
			try {
				object.put(key, value);
				return true;
			} catch (JSONException e) {
				Res.logException(e);
			}
		}
		return false;
	}
	
	private String getString(String key) {
		return getObject().optString(key, null);
//		if (object != null) {
//			return object.optString(key, null);
//		}
//		if (jsonString != null) {
//			try {
//				object = new JSONObject(jsonString);
//				return object.optString(key, null);
//			} catch (JSONException e) {
//				Res.logException(e);
//				return null;
//			}
//		}
//		return null;
	}
	
	/**
	 * @return the midState
	 */
	public String getMidstate() {
		if (midstate == null) {
			midstate = getString("midstate");
		}
		return midstate;
	}
	/**
	 * @param midState the midState to set
	 */
	public void setMidState(final String midstate) {
		if (setField("midstate", midstate))
			this.midstate = midstate;
	}
	/**
	 * @return the data
	 */
	public String getData() {
		if (data == null) {
			data = getString("data");
		}
		return data;
	}
	
	/**
	 * 
	 * @return the part of the data String that is essentially unique.  Consists of the merkle_root + timestamp.
	 * Merkle_root is unique per source and only changes when the source alters the block by adding new transactions or altering extraNonce (which I don't know how to do yet).  Timestamp appears to change
	 * multiple times/sec.  Use this for checking uniqueness before caching and then for retrieving, seems to get a duplicate rejection rate close to 0%.  
	 */
	public UniquePortionString getDataUniquePortion() {

		if (dataUniquePortion == null) {
			if (data != null && data.length() > 137) {
				dataUniquePortion = new UniquePortionString(data.substring(72, 137));
				return dataUniquePortion;
			} else if (object == null && jsonString != null) {
				//unlikely this path will ever be used while JsonRpcResponse constructor
				//forces a parse.
				//we'll try extracting the string without parseing the whole thing into
				//a JSONObject first
				int start = jsonString.indexOf("data");
				if (start != -1) {
					start = jsonString.indexOf(':', start);
					if (start != -1) {
						start = jsonString.indexOf('"', start) + 1;
						if (start != 0) {
							dataUniquePortion = new UniquePortionString(jsonString.substring(start + 72, start + 137));
							return dataUniquePortion;
						}
					}
				}
			}
			dataUniquePortion = new UniquePortionString(getData().substring(72, 137));
		}
		return dataUniquePortion;
	}
	
	/**
	 * @param data the data to set
	 */
	public void setData(final String data) {
		if (setField("data", data))
			this.data = data;
	}
	
	/**
	 * Fast set method that bypasses updating the backing JSONObject.  If you call this method
	 * this work object CANNOT BE RELIED ON TO RETURN A VALID JSONObject.
	 * @param data the data to set
	 */
	public void setDataOnly(final String data) {
		this.data = data;
	}
	
	/**
	 * @return the hash1
	 */
	public String getHash1() {
		if (hash1 == null) {
			hash1 = getString("hash1");
		}
		return hash1;
	}
	/**
	 * @param hash1 the hash1 to set
	 */
	public void setHash1(String hash1) {
		if (setField("hash1", hash1))
			this.hash1 = hash1;
	}
	/**
	 * @return the target
	 */
	public String getTarget() {
		if (target == null) {
			target = getString("target");
		}
		return target;
	}
	/**
	 * @param target the target to set
	 */
	public void setTarget(String target) {
		if (setField("target", target))
			this.target = target;
	}
	
	
	

	/**
	 * @return the object
	 */
	public JSONObject getObject() {
		if (object == null) {
			try {
			if (jsonString != null) {
				object = new JSONObject(jsonString);
			}
			if (object == null) {
				object = new JSONObject();
				object.put("hash1", hash1);
				object.put("data", data);
				object.put("midstate", midstate);
				object.put("target", target);
			}
			} catch (JSONException e) {
				Res.logException(e);
			}
			
		}
		return object;
	}
	
	public String getJSONString() {
		if (jsonString == null)
			jsonString = getObject().toString();
		return jsonString;
	}
	
	
	
}
