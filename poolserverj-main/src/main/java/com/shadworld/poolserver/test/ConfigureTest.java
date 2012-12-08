package com.shadworld.poolserver.test;

import com.google.bitcoin.core.PSJBlock;
import com.shadworld.poolserver.conf.Res;
import com.shadworld.utils.L;

public class ConfigureTest {

	public static void configureTest() {
		//Res.setTestNet();
		Res.setEasytDifficultyTarget(PSJBlock.EASIEST_DIFFICULTY_TARGET());
		L.println("easy target set to: " + Res.getEasyDifficultyTargetAsString());
		//Res.setEasytDifficultyTarget();
		
	}
	
}
