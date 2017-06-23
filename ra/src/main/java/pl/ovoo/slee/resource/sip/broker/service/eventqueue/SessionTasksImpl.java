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

import java.util.EventObject;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * This is basic implementation of the session SessionTasks.
 * It keeps new and pending events (timers, requests, responses, etc)
 * in this session events queue.
 * This SessionTasks process the current events until the queue is empty.
 * New events might jump in while executing existing events,
 * in such case they are added to the queue and executed within this thread.
 * If a new event comes when the task is not executing and not enqueued,
 * the method enqueueAndExecute returns. In this case the producer thread
 * should put this executor instance for execution in the external BlockingQueue.
 */
public class SessionTasksImpl implements SessionTasks {

    private RunStatus itsStatus = RunStatus.EMPTY;
    private final SessionEventHandler eventHandler;
    private final Queue<EventObject> queue = new LinkedList<>();
    private final Logger logger;

    public SessionTasksImpl(SessionEventHandler eventHandler) {
        this.eventHandler = eventHandler;
        logger = eventHandler.getSessionLogger(getClass());
    }

    @Override
    public synchronized void enqueueAndExecute(EventObject event, ThreadPoolExecutor executor) {
        boolean firstEvent = false;
        if (itsStatus == RunStatus.EMPTY) {
            itsStatus = RunStatus.ENQUEUED;
            firstEvent = true;
        }
        queue.add(event);
        logger.debug("Event {} enqueued, current queue status: {}", event.getClass().getSimpleName(), itsStatus);

        if (firstEvent) {
            // status changed from empty to enqueued,
            // must put this session tasks into execution
            executor.execute(this);
        }
    }

    /*
     * Check if there are any new messages to process
     * Update status to WAITING if no more messages
     *
     * @return true if any message in the eventqueue
     */
    private synchronized EventObject getNextEvent() {
        EventObject event = queue.poll();
        if (event == null) {
            // no more messages to process
            // status updated here in order to be possible to enqueue message
            // in case of concurrent thread is calling enqueueAndExecute now
            itsStatus = RunStatus.EMPTY;
        } else {
            itsStatus = RunStatus.RUNNING;
        }
        return event;
    }

    @Override
    public void run() {
        logger.debug("Starting executor");
        EventObject event = getNextEvent();
        while (event != null) {
            logger.debug("Starting event processing: {}", event.getClass().getSimpleName());
            eventHandler.handleNextEvent(event);
            event = getNextEvent();
        }
    }
}
