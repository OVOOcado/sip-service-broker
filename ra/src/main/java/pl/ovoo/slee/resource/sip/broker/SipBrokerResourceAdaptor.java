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
package pl.ovoo.slee.resource.sip.broker;

import gov.nist.javax.sip.SipListenerExt;
import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.service.SessionManager;
import pl.ovoo.slee.resource.sip.broker.service.SipBrokerContext;
import pl.ovoo.slee.resource.sip.broker.service.SipMessageListener;
import pl.ovoo.slee.resource.sip.broker.service.config.BrokerConfiguration;
import pl.ovoo.slee.resource.sip.broker.service.config.OrchestrationConfig;
import pl.ovoo.slee.resource.sip.broker.utils.SipBrokerLogger;

import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.slee.Address;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;
import java.text.ParseException;
import java.util.Properties;
import java.util.TooManyListenersException;


public class SipBrokerResourceAdaptor implements ResourceAdaptor {

    private static final String JAVAX_SIP_PORT = "javax.sip.PORT";
    private static final String SIP_TRACE_LEVEL = "gov.nist.javax.sip.TRACE_LEVEL";
    private static final String JAVAX_SIP_TRANSPORT = "javax.sip.TRANSPORT";
    private static final String BROKER_CONFIGURATION_FILE = "BROKER_CONFIGURATION_FILE";
    private static final String BROKER_HOSTNAME = "BROKER_HOSTNAME";
    private static final String IM_SCF_HOST = "IM_SCF_HOST";
    private static final String IM_SCF_PORT = "IM_SCF_PORT";
    private static final String BROKER_QUEUE_MAX_SIZE = "BROKER_QUEUE_MAX_SIZE";
    private static final String BROKER_QUEUE_INITIAL_THREADS = "BROKER_QUEUE_INITIAL_THREADS";
    private static final String BROKER_QUEUE_MAX_THREADS = "BROKER_QUEUE_MAX_THREADS";
    private static final String BROKER_QUEUE_THREAD_KEEP_ALIVE = "BROKER_QUEUE_THREAD_KEEP_ALIVE";
    private static final String STACK_NAME_BIND = "javax.sip.STACK_NAME";
    private static final String SIP_OUTGOING_RETRANSMIT_TIMER = "SIP_OUTGOING_RETRANSMIT_TIMER";
    private static final String SIP_STACK_IMPL_PATH = "gov.nist";

    // keeps all the broker settings/parameters
    private BrokerConfiguration brokerConfig  = new BrokerConfiguration();
    private SipBrokerContext brokerContext;
    private SessionManager sessionManager;
    private ResourceAdaptorContext raContext;
    private Logger logger;
    private String configurationFile;
    private int queueMaxSize;
    private int queueMaxThreads;
    private int queueInitialThreads;
    private int queueThreadKeepAlive;
    private int sipTraceLevel;
    private SipFactory sipFactory = null;
    private SipStack sipStack = null;
    private SipProvider sipProvider;

    ////////////////////////////////////////////////////////////////////////////////////////////////////
    // Begin ResourceAdaptor methods implementation
    public void setResourceAdaptorContext(javax.slee.resource.ResourceAdaptorContext resourceAdaptorContext) {
        raContext = resourceAdaptorContext;
        logger = new SipBrokerLogger(raContext.getTracer(this.getClass().getSimpleName()), null);
        logger.info("setResourceAdaptorContext: {}", resourceAdaptorContext.getEntityName());
    }

    public void unsetResourceAdaptorContext() {
        // no implementation yet
    }

    public void raConfigure(javax.slee.resource.ConfigProperties props) {
        logger.trace("raConfigure");

        brokerConfig.setPort((Integer) props.getProperty(JAVAX_SIP_PORT).getValue());
        brokerConfig.setTransport( (String) props.getProperty(JAVAX_SIP_TRANSPORT).getValue());
        brokerConfig.setBrokerHostname( (String) props.getProperty(BROKER_HOSTNAME).getValue());
        brokerConfig.setImScfHost( (String) props.getProperty(IM_SCF_HOST).getValue());
        brokerConfig.setImScfPort( (Integer) props.getProperty(IM_SCF_PORT).getValue());
        brokerConfig.setRetransmitTimer((Integer) props.getProperty(SIP_OUTGOING_RETRANSMIT_TIMER).getValue());
        configurationFile = (String) (props.getProperty(BROKER_CONFIGURATION_FILE).getValue());
        queueMaxSize = (Integer) (props.getProperty(BROKER_QUEUE_MAX_SIZE).getValue());
        queueInitialThreads = (Integer) (props.getProperty(BROKER_QUEUE_INITIAL_THREADS).getValue());
        queueMaxThreads = (Integer) (props.getProperty(BROKER_QUEUE_MAX_THREADS).getValue());
        queueThreadKeepAlive = (Integer) (props.getProperty(BROKER_QUEUE_THREAD_KEEP_ALIVE).getValue());

        ConfigProperties.Property traceLevelProperty = props.getProperty(SIP_TRACE_LEVEL);
        if(traceLevelProperty != null){
            sipTraceLevel = (Integer) (traceLevelProperty.getValue());
        } else {
            sipTraceLevel = -1;
        }

        logger.debug("Configuration loaded: {}", configurationFile);

        if(brokerContext != null){
            OrchestrationConfig orchestrationConf = new OrchestrationConfig(logger);
            orchestrationConf.loadConfig(configurationFile, brokerContext.addressFactory, brokerContext.headerFactory);
            brokerContext.updateConfig(orchestrationConf);
        }
    }

    public void raUnconfigure() {
        // nothing to unconfigure
    }

    public void raActive() {
        logger.debug("raActive initializing Broker SIP Resource Adapter: {}", raContext.getEntityName());
        logger.debug("Init stack on port: {}, address: {}", brokerConfig.getPort(), brokerConfig.getBrokerHostname());

        try {
            SipMessageListener sipMessageListener = new SipMessageListener();
            initStack(sipMessageListener);

            brokerContext = new SipBrokerContext(raContext, brokerConfig, sipFactory, sipProvider);
            sessionManager = new SessionManager(brokerContext);
            brokerContext.setSessionManager(sessionManager);

            OrchestrationConfig orchestrationConf = new OrchestrationConfig(logger);
            orchestrationConf.loadConfig(configurationFile, brokerContext.addressFactory, brokerContext.headerFactory);
            brokerContext.updateConfig(orchestrationConf);

            sipMessageListener.initListener(brokerContext, queueMaxSize, queueInitialThreads, queueMaxThreads, queueThreadKeepAlive);

            logger.debug("Broker SIP Resource Adapter initialized");
        } catch (IllegalArgumentException | TooManyListenersException | InvalidArgumentException | SipException |
                    ParseException e) {
            logger.error("Unable to initialize Broker SIP Resource Adapter", e);
        }
    }


    private void initStack(SipListenerExt listener)
            throws InvalidArgumentException, TooManyListenersException, SipException {
        logger.trace("initStack");

        Properties properties = new Properties();
        if(sipTraceLevel >= 0){
            properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", Integer.toString(sipTraceLevel));
        }
        properties.setProperty(STACK_NAME_BIND, raContext.getEntityName());

        sipFactory = SipFactory.getInstance();
        sipFactory.setPathName(SIP_STACK_IMPL_PATH);
        sipStack = sipFactory.createSipStack(properties);
        sipStack.start();

        ListeningPoint lp = sipStack.createListeningPoint(brokerConfig.getBrokerHostname(),
                                                            brokerConfig.getPort(), brokerConfig.getTransport());
        sipProvider = sipStack.createSipProvider(lp);
        sipProvider.addSipListener(listener);
        logger.info("SIP Stack ready");
    }


    public void raStopping() {
        logger.debug("Stopping sip stack ");

        if(sessionManager!=null) {
            sessionManager.printManagerStatus();
            sessionManager.removeAllSessions();
        }

        if(sipStack != null){
            sipStack.stop();
        }

    }

    public void raInactive() {
        // no action for raInactive
    }

    public void raVerifyConfiguration(javax.slee.resource.ConfigProperties configProperties)
            throws InvalidConfigurationException {
        // no action for raVerifyConfiguration
    }

    public void raConfigurationUpdate(javax.slee.resource.ConfigProperties configProperties) {
        // configuration allowed on the fly
        raConfigure(configProperties);
    }

    public Object getResourceAdaptorInterface(String s) {
        return null;
    }

    public javax.slee.resource.Marshaler getMarshaler() {
        return null;
    }

    public void serviceActive(javax.slee.resource.ReceivableService receivableService) {
        // no action for serviceActive
    }

    public void serviceStopping(javax.slee.resource.ReceivableService receivableService) {
        // no action for serviceStopping
    }

    public void serviceInactive(javax.slee.resource.ReceivableService receivableService) {
        // no action for serviceInactive
    }

    public void queryLiveness(javax.slee.resource.ActivityHandle activityHandle) {
        // no action for queryLiveness
    }

    public Object getActivity(javax.slee.resource.ActivityHandle activityHandle) {
        return null;
    }

    public javax.slee.resource.ActivityHandle getActivityHandle(Object o) {
        return null;
    }

    public void administrativeRemove(javax.slee.resource.ActivityHandle activityHandle) {
        // no action for administrativeRemove
    }

    public void eventProcessingSuccessful(javax.slee.resource.ActivityHandle activityHandle, javax.slee.resource
            .FireableEventType fireableEventType, Object o, Address address, javax.slee.resource.ReceivableService
            receivableService, int i) {
        // no action for eventProcessingSuccessful
    }

    public void eventProcessingFailed(javax.slee.resource.ActivityHandle activityHandle, javax.slee.resource
            .FireableEventType fireableEventType, Object o, Address address, javax.slee.resource.ReceivableService
            receivableService, int i, javax.slee.resource.FailureReason failureReason) {
        // no action for eventProcessingFailed
    }

    public void eventUnreferenced(javax.slee.resource.ActivityHandle activityHandle, javax.slee.resource
            .FireableEventType fireableEventType, Object o, Address address, javax.slee.resource.ReceivableService
            receivableService, int i) {
        // no action for eventUnreferenced
    }

    public void activityEnded(javax.slee.resource.ActivityHandle activityHandle) {
        // no action for activityEnded
    }

    public void activityUnreferenced(javax.slee.resource.ActivityHandle activityHandle) {
        // no action for activityUnreferenced
    }
    // End ResourceAdaptor methods implementation
}
