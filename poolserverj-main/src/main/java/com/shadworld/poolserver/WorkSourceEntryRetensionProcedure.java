package com.shadworld.poolserver;

import com.shadworld.poolserver.entity.UniquePortionString;

import gnu.trove.procedure.TObjectObjectProcedure;

public class WorkSourceEntryRetensionProcedure implements TObjectObjectProcedure<UniquePortionString, WorkSourceEntry> {

	long compareTime;
	
	@Override
	public boolean execute(UniquePortionString a, WorkSourceEntry b) {
		return b.createTime > compareTime;
	}

}
