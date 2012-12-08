package com.shadworld.poolserver;

import java.util.Comparator;

import com.shadworld.poolserver.source.WorkSource;
import com.shadworld.poolserver.stats.Stats;

final class SourceUtilisationComparator implements Comparator<WorkSource> {
	@Override
	public int compare(WorkSource o1, WorkSource o2) {
		Stats stats = Stats.get();
		double totalWork = stats.getTotalWorkDelivered();
		double o1Usage = stats.getSourceStats(o1).getDeliveredWorkThisBlock() / totalWork;
		double o2Usage = stats.getSourceStats(o2).getDeliveredWorkThisBlock() / totalWork;
		double o1Utilisation = o1Usage / o1.getWeightingPerc();
		double o2Utilisation = o2Usage / o2.getWeightingPerc();
		if (o1Utilisation > o2Utilisation)
			return 1;
		if (o2Utilisation > o1Utilisation)
			return -1;
		return 0;
	}
}