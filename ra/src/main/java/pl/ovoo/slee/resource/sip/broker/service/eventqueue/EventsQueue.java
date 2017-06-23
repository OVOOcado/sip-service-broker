/*
 * SIP Service Broker Resource Adaptor
 * Copyright (C) 2016-2017 "OVOO Sp. z o.o."
 *
 * This file is part of the SIP Service Broker RA.
 *
 * SIP Service Broker RA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * SIP Service Broker RA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pl.ovoo.slee.resource.sip.broker.service.eventqueue;

import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.service.SipBrokerContext;

import java.util.EventObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This is the eventqueue implementation that handles all the events.
 * The events are synchronized on session level by means of executors.
 */
public class EventsQueue {

    private final Logger logger;
    private final BlockingQueue<Runnable> eventsBlockingQueue;

    // TODO: add rejection policy to this executor
    // probably by sending SIP error response like 486 Busy Here
    private final ThreadPoolExecutor eventsExecutor;

    /**
     * @param brokerContext - sip broker context
     * @param queueMaxSize - the capacity of this queue
     * @param queueInitialThreads - the number of threads to keep in the pool
     * @param queueMaxThreads - the maximum number of threads to allow in the pool
     * @param queueThreadKeepAlive - maximum time that excess idle threads will wait for new tasks before terminating
     */
    public EventsQueue(SipBrokerContext brokerContext, int queueMaxSize, int queueInitialThreads,
                       int queueMaxThreads, int queueThreadKeepAlive) {

        eventsBlockingQueue = new LinkedBlockingQueue<>(queueMaxSize);

        logger = brokerContext.getLogger(this.getClass());
        eventsExecutor = new ThreadPoolExecutor(queueInitialThreads, queueMaxThreads,
                                            queueThreadKeepAlive, TimeUnit.MILLISECONDS, eventsBlockingQueue);
    }


    /**
     * This method adds event to process and enqueues session executor for processing.
     * If there is a pending thread executing this session executor, the event is added to that running executor.
     *
     * @param event   - an event to enqueue
     * @param sessionHandler - session handler to correlate
     */
    public void enqueueEvent(EventObject event, SessionEventHandler sessionHandler) {
        if(sessionHandler == null){
            logger.warn("No session handler found for event {}", event.getClass().getSimpleName());
            return;
        }
        SessionTasks sessionTasks = sessionHandler.getItsSessionTasks();
        sessionTasks.enqueueAndExecute(event, eventsExecutor);
        if(logger.isDebugEnabled()){
            Object[] args = new Object[]{sessionHandler.getID(),
                    eventsBlockingQueue.size(),
                    eventsExecutor.getActiveCount()};
            logger.debug("Enqueued SessionTasks for {}, BlockingQueue size: {}, Executor ActiveCount: {}",args);
        }
    }
}
