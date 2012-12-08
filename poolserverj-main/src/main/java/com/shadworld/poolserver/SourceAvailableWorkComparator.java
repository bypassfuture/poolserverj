package com.shadworld.poolserver;

import java.util.Comparator;

import com.shadworld.poolserver.source.WorkSource;

final class SourceAvailableWorkComparator implements Comparator<WorkSource> {
	@Override
	public int compare(WorkSource o1, WorkSource o2) {
		return o1.getWorksAvailable() - o2.getWorksAvailable();
	}
}