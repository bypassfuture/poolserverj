package com.shadworld.poolserver.source;

import gnu.trove.procedure.TObjectLongProcedure;

import com.shadworld.poolserver.entity.UniquePortionString;

class DuplicateRetensionPolicy implements TObjectLongProcedure<UniquePortionString> {

	long compareTime;
	
	@Override
	public boolean execute(UniquePortionString a, long b) {
		return b > compareTime;
	}
	
}