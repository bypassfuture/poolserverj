package com.shadworld.poolserver.db.worker;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.shadworld.poolserver.entity.Worker;

public class AnyWorkerFetchEngine extends WorkerDBFetchEngine {

        public AnyWorkerFetchEngine(String[] extraParams) {
                super(extraParams);
        }

        /* (non-Javadoc)
         * @see com.shadworld.poolserver.db.worker.WorkerDBFetchEngine#fetchWorkersById(java.util.List)
         */
        @Override
        public List<Worker> fetchWorkersById(List<Integer> ids) throws SQLException {
                return new ArrayList<Worker>();
        }

        /* (non-Javadoc)
         * @see com.shadworld.poolserver.db.worker.WorkerDBFetchEngine#fetchWorker(java.lang.String)
         */
        @Override
        public Worker fetchWorker(String username) throws SQLException {
                Worker worker = new Worker();
                worker.setUsername(username);
                worker.setPassword("");
                worker.setId(1);
                return worker;
        }
        
}
