/*******************************************************************************
 *  
 *   Copyright 2015 Walmart, Inc.
 *  
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *  
 *       http://www.apache.org/licenses/LICENSE-2.0
 *  
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  
 *******************************************************************************/
package com.oneops.opamp.jms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.oneops.cms.exceptions.OpsException;
import com.oneops.opamp.exceptions.OpampException;
import com.oneops.opamp.service.BadStateProcessor;
import com.oneops.opamp.service.EnvPropsProcessor;
import com.oneops.opamp.service.FlexStateProcessor;
import com.oneops.opamp.service.Notifications;
import com.oneops.opamp.util.EventUtil;
import com.oneops.ops.CiOpsProcessor;
import com.oneops.ops.dao.OpsCiStateDao;
import com.oneops.ops.events.CiChangeStateEvent;
import com.oneops.ops.events.OpsBaseEvent;

/**
 * The listener for opsevent generated by the sensor @see CiChangeStateEvent 
 * Responsible for notifying events, 
 * trigger auto-repairs,auto-scales 
 * 
 */
public class OpsEventListener implements MessageListener {

	private static Logger logger = Logger.getLogger(OpsEventListener.class);
	
	private Gson gson;
	private Notifications notifier;
	private BadStateProcessor bsProcessor;
	private FlexStateProcessor fsProcessor;
	private EnvPropsProcessor envProcessor;
	private OpsCiStateDao opsCiStateDao;
	private EventUtil eventUtil;
	private CiOpsProcessor ciOpsProcessor;
	
	/**
	 * Sets the fs processor.
	 *
	 * @param fsProcessor the new fs processor
	 */
	public void setFsProcessor(FlexStateProcessor fsProcessor) {
		this.fsProcessor = fsProcessor;
	}

	/**
	 * Sets the bs processor.
	 *
	 * @param bsProcessor the new bs processor
	 */
	public void setBsProcessor(BadStateProcessor bsProcessor) {
		this.bsProcessor = bsProcessor;
	}
	
	/**
	 * Sets the env processor.
	 * 
	 * @param envProcessor
	 */
	public void setEnvProcessor(EnvPropsProcessor envProcessor) {
		this.envProcessor = envProcessor;
	}
	
	public OpsCiStateDao getOpsCiStateDao() {
		return opsCiStateDao;
	}

	public void setOpsCiStateDao(OpsCiStateDao opsCiStateDao) {
		this.opsCiStateDao = opsCiStateDao;
	}

	/**
	 * Sets the notifier.
	 *
	 * @param notifier the new notifier
	 */
	public void setNotifier(Notifications notifier) {
		this.notifier = notifier;
	}

	/**
	 * Sets the gson.
	 *
	 * @param gson the new gson
	 */
	public void setGson(Gson gson) {
		this.gson = gson;
	}

	/** when a message arrives
	 * @see javax.jms.MessageListener#onMessage(javax.jms.Message)
	 */
	public void onMessage(Message msg) {
		try {
				
			logger.debug(msg);
			if (!envProcessor.isOpAmpSuspended() && (msg instanceof TextMessage)) {
				try {
					String type = msg.getStringProperty("type");

					if ("ci-change-state".equals(type)) {
						CiChangeStateEvent event = gson.fromJson(((TextMessage)msg).getText(), CiChangeStateEvent.class);
						
						eventUtil.addCloudName(event, envProcessor.fetchDeployedToRelations(event.getCiId()));
						
						OpsBaseEvent opsEvent = eventUtil.getOpsEvent(event);
						long manifestId = opsEvent.getManifestId();
						List<Long> manifestIds = new ArrayList<>();
						manifestIds.add(manifestId);
						Map<String, Integer> counters = ciOpsProcessor.getManifestStates(manifestIds).get(manifestId);
						if (counters != null) {
							logger.info("component level state counters for bom cid " + event.getCiId() + ": "+ counters);
							event.setComponentStatesCounters(counters);
						} else {
							logger.warn("state counters found null for " + event.getCiId());
						}
						
						boolean isNewState = (event.getNewState() != null) && (!event.getNewState().equals(event.getOldState()));

						//Changed to pass the ChangeEvent context event
					    if (event.getPayLoad() != null && event.getNewState().equals(event.getOldState()) && opsEvent.getState().equalsIgnoreCase("close")) {
							//this is the situation when one threshold got cleared but 
					    	//the ci state didn't changed b/c of other threshold is still violated
							//we just need to send the notification.
					    	logger.info("sendingOpsCloseEventNotification for cid: " + event.getCiId() + " " + opsEvent.getSource() + " status " + opsEvent.getStatus() + " ostate:"
									+ event.getOldState() + " nstate: " + event.getNewState() );
					    	notifier.sendOpsEventNotification(event);
					    } else if ("unhealthy".equals(event.getNewState())) {
					    	if (opsEvent != null && opsEvent.getType() != null 
					    			&& "heartbeat".equals(opsEvent.getType())
					    			&& envProcessor.isHeartbeatAlarmSuspended()) {
					    		logger.warn("Heartbeat alarms suppressed. "
					    				+ "No notifications/auto-repair/auto-replace will be performed for missing heartbeats");
					    	} else{
					    		bsProcessor.processUnhealthyState(event);	
					    	}
						} else if ("overutilized".equals(event.getNewState())) {
							fsProcessor.processOverutilized(event, isNewState);
						} else if ("underutilized".equals(event.getNewState())) {
							fsProcessor.processUnderutilized(event, isNewState, event.getTimestamp());
						} else if (event.getPayLoad() != null &&  "notify".equals(event.getNewState()) && eventUtil.shouldNotify(event, opsEvent) )   {
							//skip the notification in case payload is null
							notifier.sendOpsEventNotification(event);
						} else if ("good".equals(event.getNewState()) && "unhealthy".equals(event.getOldState())) {
							logger.info("sending good notification for cid: " + event.getCiId() + " " + (opsEvent!=null ? opsEvent.getSource():"") + " status " + (opsEvent!=null ? opsEvent.getStatus():"") + " ostate:"
									+ event.getOldState() + " nstate: " + event.getNewState() );
							bsProcessor.processGoodState(event);								
						} else if (event.getPayLoad() != null && "good".equals(event.getNewState()) 
								&& "notify".equals(event.getOldState())) {//skip the notification in case payload is null
							logger.info("sending recoverynotification for cid: " + event.getCiId() + " " + (opsEvent!=null ? opsEvent.getSource():"") + " status " + (opsEvent!=null ? opsEvent.getStatus():"") + " ostate:"
									+ event.getOldState() + " nstate: " + event.getNewState() );
							notifier.sendOpsEventNotification(event);
						}	
					}
				} catch (OpsException opse) {
					logger.error("OpsException in onMessage", opse);
				} catch (OpampException e) {
					logger.error("The message could not be processed "+msg);
					logger.error("OpampException in onMessage", e);				}
			}
			msg.acknowledge();
		} catch (JMSException e) {
		    logger.error("Exception occured while proccesing the message"+msg,e);
		}
	}

	public EventUtil getEventUtil() {
		return eventUtil;
	}

	public void setEventUtil(EventUtil eventUtil) {
		this.eventUtil = eventUtil;
	}

	public CiOpsProcessor getCiOpsProcessor() {
		return ciOpsProcessor;
	}

	public void setCiOpsProcessor(CiOpsProcessor ciOpsProcessor) {
		this.ciOpsProcessor = ciOpsProcessor;
	}
}
