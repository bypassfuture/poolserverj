package com.shadworld.poolserver;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

import com.shadworld.poolserver.entity.FastEqualsSolution;
import com.shadworld.poolserver.entity.Work;
import com.shadworld.poolserver.source.WorkSource;

public class WorkSourceEntry implements Serializable {
	
	transient WorkSource source;
	String sourceName;
	ArrayList<FastEqualsSolution> solutions;
	FastEqualsSolution firstSolution;
	//Work work;
	long createTime;
	int blockNum;

	public WorkSourceEntry(WorkSource source, long createTime, long blockNum) {
		super();
		//this.work = work;
		reset(source, createTime, blockNum);
	}
	
	public void reset(WorkSource source, long createTime, long blockNum) {
		this.source = source;
		if (source == null) {
			throw new IllegalStateException("Source cannot be null");
		}
		this.createTime = createTime;
		this.blockNum = (int) blockNum;
		if (this.solutions != null) {
			if (solutions.size() > 10)
				solutions = null;
			else
				solutions.clear();
		}
	}

	/**
	 * @return the source
	 */
	public WorkSource getSource() {
		return source;
	}

//	/**
//	 * @return the work
//	 */
//	public Work getWork() {
//		return work;
//	}

	/**
	 * @return the createTime
	 */
	public long getCreateTime() {
		return createTime;
	}
	
	private void writeObject(ObjectOutputStream out) throws IOException {
		sourceName = source.getName();
		out.defaultWriteObject();
	}
	
	private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
		in.defaultReadObject();
		source = PoolServer.get().getWorkProxy().getSourceByName(sourceName);
		sourceName = null;
	}

}