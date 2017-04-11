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
package pl.ovoo.slee.resource.sip.broker.utils;

import gov.nist.javax.sip.Utils;
import gov.nist.javax.sip.header.ContentLength;
import gov.nist.javax.sip.header.HeaderExt;
import org.slf4j.Logger;
import pl.ovoo.slee.resource.sip.broker.service.SipBrokerContext;

import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.InvalidArgumentException;
import javax.sip.SipException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Set of helper functions to create consistent requests responses.
 */
public class MessageUtils {

    private static final String P_ORIGINAL_DIALOG_ID = "P-Original-Dialog-ID";
    private static final int DEFAULT_MAX_FORWARDS = 70;
    private static final AtomicLong sequenceNumber = new AtomicLong(0L);
    private static final List<String> EXCLUDED_RESPONSE_HEADERS = new ArrayList<>();
    private static final List<String> EXCLUDED_REQUEST_HEADERS = new ArrayList<>();
    private static final List<String> EXCLUDED_ACK_HEADERS = new ArrayList<>();
    private static final String CALL_ID_EQ = "call-id";
    private static final String SEMI_FROM_TAG_EQ = ";FROM_TAG=";
    private static final String SEMI_TO_TAG_EQ = ";TO_TAG=";


    static {
        EXCLUDED_RESPONSE_HEADERS.add(FromHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(ToHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(CallIdHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(CSeqHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(ViaHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(RouteHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(RecordRouteHeader.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(ContentLength.NAME);
        EXCLUDED_RESPONSE_HEADERS.add(ContactHeader.NAME);

        EXCLUDED_REQUEST_HEADERS.add(FromHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(ToHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(CallIdHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(CSeqHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(ViaHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(RouteHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(RecordRouteHeader.NAME);
        EXCLUDED_REQUEST_HEADERS.add(ContentLength.NAME);
        EXCLUDED_REQUEST_HEADERS.add(ContactHeader.NAME);

        EXCLUDED_ACK_HEADERS.add(FromHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(ToHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(CallIdHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(CSeqHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(ViaHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(RouteHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(RecordRouteHeader.NAME);
        EXCLUDED_ACK_HEADERS.add(ContentLength.NAME);
        EXCLUDED_ACK_HEADERS.add(ContactHeader.NAME);
    }


    private MessageUtils(){
        // only static access
    }

    /**
     * Prepares a new response to send back to previous AS (or IM-SCF).
     * It copies proper headers and body from Response incoming from other AS (or IM-SCF)
     *
     * @param incomingResponse    - response received from previous node
     * @param dialog              - dialog for the response
     * @param request             - request to respond to
     * @param messageFactory      - MessageFactory
     * @param brokerContactHeader - broker contact header
     * @param logger              - logger instance
     * @return a new Response to send
     */
    public static Response createForwardedResponse(Response incomingResponse, Dialog dialog, Request request,
                                                   MessageFactory messageFactory, ContactHeader brokerContactHeader,
                                                   Logger logger) throws ParseException {

        Response newResponse = messageFactory.createResponse(incomingResponse.getStatusCode(), request);
        copyResponseHeaders(incomingResponse, newResponse, dialog, brokerContactHeader);

        logger.debug("Prepared Response:\n{}", newResponse);
        return newResponse;
    }


    /**
     * Prepares a new reliable provisional response to send back to previous AS (or IM-SCF).
     * It copies proper headers and body from Response incoming from other AS (or IM-SCF)
     *
     * @param incomingResponse    - response received from previous node
     * @param dialog              - dialog for the response
     * @param brokerContactHeader - broker contact header
     * @param logger              - logger instance
     * @return a new Response to send
     */
    public static Response createForwardedReliableResponse(Response incomingResponse, Dialog dialog,
                                                           ContactHeader brokerContactHeader, Logger logger)
                            throws ParseException, SipException, InvalidArgumentException {

        Response reliableResponse = dialog.createReliableProvisionalResponse(incomingResponse.getStatusCode());
        copyResponseHeaders(incomingResponse, reliableResponse, dialog, brokerContactHeader);

        logger.debug("Prepared Response:\n{}", reliableResponse);
        return reliableResponse;
    }


    /**
     * Prepares a new INVITE request to send to the next AS (or outgoing IM-SCF dialog).
     * It copies proper headers and body from Request incoming from other AS (or IM-SCF)
     *
     * @param context           - broker context
     * @param incomingRequest   - INVITE Request sent by the other AS (or IM-SCF)
     * @param logger            - logger
     *
     * @return a new Request to send
     */
    public static Request createInvite(SipBrokerContext context, Request incomingRequest,
                                       List<RouteHeader> routeHeaders, Logger logger, CallIdHeader callId)
                                throws ParseException {
        logger.trace("createInvite");

        FromHeader fromHeader = (FromHeader) incomingRequest.getHeader(FromHeader.NAME);
        ToHeader toHeader = (ToHeader) incomingRequest.getHeader(ToHeader.NAME);
        ContentTypeHeader contentTypeHeader = (ContentTypeHeader) incomingRequest.getHeader(ContentTypeHeader.NAME);
        MaxForwardsHeader maxForwardsHeader;
        CSeqHeader cSeqHeader;
        Request newInvite;
        try {
            maxForwardsHeader = context.headerFactory.createMaxForwardsHeader(DEFAULT_MAX_FORWARDS);
            cSeqHeader = context.headerFactory.createCSeqHeader(sequenceNumber.incrementAndGet(),
                    Request.INVITE);

            if(contentTypeHeader != null){
                newInvite = context.messageFactory.createRequest(incomingRequest.getRequestURI(), Request.INVITE,
                        callId, cSeqHeader, fromHeader, toHeader, new ArrayList<ViaHeader>(),
                        maxForwardsHeader, contentTypeHeader, incomingRequest.getContent());
            } else {
                newInvite = context.messageFactory.createRequest(incomingRequest.getRequestURI(), Request.INVITE,
                        callId, cSeqHeader, fromHeader, toHeader, new ArrayList<ViaHeader>(),
                        maxForwardsHeader);
            }

        } catch (ParseException | InvalidArgumentException e) {
            // either ParseException or InvalidArgumentException
            // will never happen for DEFAULT_MAX_FORWARDS constant and CSeq long
            throw new IllegalArgumentException(e);
        }

        // copy all non-required headers
        copyRequestHeaders(incomingRequest, newInvite, context.getBrokerContactHeader());

        // add proper Route headers to the bottom (from the incoming request)
        for (RouteHeader routeHeader: routeHeaders) {
            logger.trace("Adding Route header: {}", routeHeader);
            newInvite.addHeader(routeHeader);
        }

        logger.debug("New INVITE request prepared: \n{}", newInvite);
        return newInvite;
    }


    /**
     * Prepares a new request to send to the next node
     *
     * @param incomingRequest     - incoming request to forward
     * @param outgoingDialog      - dialog to send request on
     * @param brokerContactHeader - broker contact header
     * @param logger              - logger
     *
     * @return a new request to send on a dialog
     */
    public static Request createOnDialogRequest(Request incomingRequest, Dialog outgoingDialog,
                                                ContactHeader brokerContactHeader, Logger logger)
                             throws ParseException, SipException {
        logger.trace("createOnDialogRequest");

        Request newRequest = outgoingDialog.createRequest(incomingRequest.getMethod());
        copyRequestHeaders(incomingRequest, newRequest, brokerContactHeader);

        logger.debug("New request: \n{}", newRequest);
        return newRequest;
    }


    /**
     * Prepares new ACK to send to the next node
     *
     * @param incomingAck         - incoming ACK to forward
     * @param dialog              - dialog to ACK
     * @param brokerContactHeader - broker contact header
     * @param logger              - logger
     *
     * @return a new ACK request to send on a dialog
     */
    public static Request createAck(Request incomingAck, long cSeq, Dialog dialog,
                                    ContactHeader brokerContactHeader, Logger logger)
                            throws ParseException, InvalidArgumentException, SipException {
        logger.trace("createOnDialogRequest");

        Request newAck = dialog.createAck(cSeq);
        copyAckHeaders(incomingAck, newAck, brokerContactHeader);

        logger.debug("Prepared new ACK: \n{}", newAck);
        return newAck;
    }


    /**
     * Prepares an OPTIONS request to send towards the endpoint
     *
     * @param context           - broker context
     * @param endpoint          - endpoint address to send OPTIONS
     * @param callId            - callId header to use
     * @param logger            - logger instance
     *
     * @return a new OPTIONS Request to send
     */
    public static Request createOptionsRequest(SipBrokerContext context, Address endpoint,
                                               CallIdHeader callId, Logger logger)
            throws ParseException, InvalidArgumentException {
        logger.trace("createOptionsRequest");

        URI requestUri = endpoint.getURI();
        ToHeader to = context.headerFactory.createToHeader(endpoint, null);
        FromHeader from = context.headerFactory.createFromHeader(context.getBrokerContactHeader().getAddress(), null);
        MaxForwardsHeader maxForwardsHeader = context.headerFactory.createMaxForwardsHeader(DEFAULT_MAX_FORWARDS);
        CSeqHeader cSeq = context.headerFactory.createCSeqHeader(sequenceNumber.incrementAndGet(), Request.OPTIONS);

        return context.messageFactory.createRequest(requestUri, Request.OPTIONS,
                callId, cSeq, from, to, new ArrayList<ViaHeader>(), maxForwardsHeader);
    }


    /**
     * Prepares default IM-SCF Route header to use in downstream INVITE requests
     *
     * @param headerFactory    - SIP HeaderFactory
     * @param addressFactory   - SIP AddressFactory
     * @param imScfHost        - IM-SCF host
     * @param imScfPort        - IM-SCF port
     *
     * @return IM-SCF Route header
     *
     * @throws ParseException
     * @throws InvalidArgumentException
     */
    public static RouteHeader createImScfRouteHeader(HeaderFactory headerFactory, AddressFactory addressFactory,
                                                          String imScfHost, int imScfPort)
                             throws ParseException {

        SipURI sipURI = addressFactory.createSipURI(null, imScfHost + ":" + imScfPort);
        sipURI.setLrParam();
        Address address = addressFactory.createAddress(sipURI);
        return headerFactory.createRouteHeader(address);
    }


    /**
     * Prepares default Broker Contact header to be used in requests/responses
     *
     * @param headerFactory     - SIP HeaderFactory
     * @param brokerHost        - broker hostname
     * @param transport         - protocol
     * @param brokerPort        - broker sip listening port
     *
     * @return broker ContactHeader
     *
     * @throws ParseException
     * @throws InvalidArgumentException
     */
    public static ContactHeader createBrokerContactHeader(HeaderFactory headerFactory, AddressFactory addressFactory,
                                                          String brokerHost, String transport, int brokerPort)
                                throws ParseException, InvalidArgumentException {

        SipURI sipURI = addressFactory.createSipURI(null, brokerHost + ":" + brokerPort);
        sipURI.setTransportParam(transport);
        Address address = addressFactory.createAddress(sipURI);
        return headerFactory.createContactHeader(address);
    }


    /**
     * Copies headers and body from previous response to the new one
     * Skips headers that are not allowed to pass back from previous response (like routing, via, etc)
     *
     * @param incomingResponse    - Response sent by the other AS (to pass backward or forward)
     * @param outgoingResponse    - outgoing response to modify
     * @param dialog              - dialog for the response
     * @param brokerContactHeader - broker contact header
     *
     * @return modified outgoing response
     *
     * @throws ParseException
     */
    @SuppressWarnings("unchecked")
    private static Response copyResponseHeaders(Response incomingResponse, Response outgoingResponse,
                                               Dialog dialog, ContactHeader brokerContactHeader) throws ParseException {

        // copy headers (excluding the ones that are disallowed)
        ListIterator<String> headerNames = incomingResponse.getHeaderNames();
        while(headerNames.hasNext()){
            String name = headerNames.next();
            if(!EXCLUDED_RESPONSE_HEADERS.contains(name)){
                outgoingResponse.removeHeader(name);
                ListIterator<Header> newHeaders = incomingResponse.getHeaders(name);
                while (newHeaders.hasNext()){
                    Header h = (Header) newHeaders.next().clone();
                    outgoingResponse.addHeader(h);
                }
            }
        }

        // add body
        if(incomingResponse.getContentLength() != null && incomingResponse.getContentLength().getContentLength() > 0){
            outgoingResponse.setContent(new String(incomingResponse.getRawContent()),
                    (ContentTypeHeader) incomingResponse.getHeader(ContentTypeHeader.NAME));
        }

        // apply new To header tag (if required/applicable)
        ToHeader to = (ToHeader) outgoingResponse.getHeader(ToHeader.NAME);
        if(to.getTag() == null &&
                ((dialog.getState() == null || dialog.getState() == DialogState.EARLY) && dialog.isServer())){
                // local tag could have been already created by previous response
                String toTag;
                if(dialog.getLocalTag() != null){
                    toTag = dialog.getLocalTag();
                } else {
                    toTag = Utils.getInstance().generateTag();
                }
                to.setTag(toTag);
        }

        // add Contact header
        Header header = incomingResponse.getHeader(ContactHeader.NAME);
        if(header != null){
            if(incomingResponse.getStatusCode() >= Response.MULTIPLE_CHOICES
                    && incomingResponse.getStatusCode() < Response.BAD_REQUEST){
                // 3xx response must pass the incoming Contact header instead of Broker's one
                outgoingResponse.setHeader(header);
            } else {
                outgoingResponse.setHeader(brokerContactHeader);
            }
        }

        return outgoingResponse;
    }


    /**
     * Copies headers and body from previous request to the new one
     * Skips headers that are not allowed to pass from previous request (like routing, via, etc)
     *
     * @param incomingRequest     - request sent by the other AS (to pass backward or forward)
     * @param outgoingRequest     - outgoing request to modify
     * @param brokerContactHeader - broker contact header
     *
     * @return modified outgoing request
     *
     * @throws ParseException
     */
    @SuppressWarnings("unchecked")
    private static Request copyRequestHeaders(Request incomingRequest, Request outgoingRequest,
                                                ContactHeader brokerContactHeader) throws ParseException {

        // copy headers (excluding the ones that are disallowed)
        ListIterator<String> headerNames = incomingRequest.getHeaderNames();
        while(headerNames.hasNext()){
            String name = headerNames.next();
            if(!EXCLUDED_REQUEST_HEADERS.contains(name)){
                outgoingRequest.removeHeader(name);
                ListIterator<Header> newHeaders = incomingRequest.getHeaders(name);
                while (newHeaders.hasNext()){
                    Header h = (Header) newHeaders.next().clone();
                    outgoingRequest.addHeader(h);
                }
            }
        }

        // add body
        if(incomingRequest.getContentLength() != null && incomingRequest.getContentLength().getContentLength() > 0){
            outgoingRequest.setContent(new String(incomingRequest.getRawContent()),
                    (ContentTypeHeader) incomingRequest.getHeader(ContentTypeHeader.NAME));
        }

        // add Contact header
        outgoingRequest.setHeader(brokerContactHeader);

        return outgoingRequest;
    }


    /**
     * Copies headers and body from previous ACK to the new one
     * Skips headers that are not allowed to pass from previous ACK (like routing, via, etc)
     *
     * @param incomingAck     - ACK sent by the other AS (to pass backward or forward)
     * @param outgoingAck     - outgoing ACK to modify
     * @param brokerContactHeader - broker contact header
     *
     * @return modified outgoing ACK request
     *
     * @throws ParseException
     */
    @SuppressWarnings("unchecked")
    private static Request copyAckHeaders(Request incomingAck, Request outgoingAck,
                                              ContactHeader brokerContactHeader) throws ParseException {

        // copy headers (excluding the ones that are disallowed)
        ListIterator<String> headerNames = incomingAck.getHeaderNames();
        while(headerNames.hasNext()){
            String name = headerNames.next();
            if(!EXCLUDED_ACK_HEADERS.contains(name)){
                outgoingAck.removeHeader(name);
                ListIterator<Header> newHeaders = incomingAck.getHeaders(name);
                while (newHeaders.hasNext()){
                    Header h = (Header) newHeaders.next().clone();
                    outgoingAck.addHeader(h);
                }
            }
        }

        // add body
        if(incomingAck.getContentLength() != null && incomingAck.getContentLength().getContentLength() > 0){
            outgoingAck.setContent(new String(incomingAck.getRawContent()),
                    (ContentTypeHeader) incomingAck.getHeader(ContentTypeHeader.NAME));
        }

        // add Contact header
        if(incomingAck.getHeader(ContactHeader.NAME) != null){
            outgoingAck.setHeader(brokerContactHeader);
        }

        return outgoingAck;
    }

    /**
     * Reads P-Original-Dialog-ID header value from the request
     * If not present a new header is created and set in the request.
     *
     * @param request - INVITE request to read header from
     *
     * @return header value
     *
     * @throws ParseException - in case error happens while parsing the values
     */
    public static String getCreateOriginalDialogId(Request request, HeaderFactory headerFactory) throws ParseException {
        Header pOdidHeader = request.getHeader(MessageUtils.P_ORIGINAL_DIALOG_ID);
        if(pOdidHeader != null){
            return ((HeaderExt) pOdidHeader).getValue();
        }

        CallIdHeader callId = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);

        String toValue = to.getTag() == null ? "0" : to.getTag();

        String pOdIdValue = CALL_ID_EQ + callId.getCallId() +
                        SEMI_TO_TAG_EQ + toValue +
                        SEMI_FROM_TAG_EQ + from.getTag();

        pOdidHeader = headerFactory.createHeader(P_ORIGINAL_DIALOG_ID, pOdIdValue);
        request.setHeader(pOdidHeader);
        return pOdIdValue;
    }



}
