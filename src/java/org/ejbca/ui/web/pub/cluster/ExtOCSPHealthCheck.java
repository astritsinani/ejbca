/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/

package org.ejbca.ui.web.pub.cluster;

import java.sql.Connection;
import java.sql.Statement;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;

import org.apache.log4j.Logger;
import org.ejbca.core.ejb.JNDINames;
import org.ejbca.core.model.log.Admin;
import org.ejbca.util.JDBCUtil;



/**
 * Ext OCSP Health Checker. 
 * 
 * Does the following system checks.
 * 
 * * Not about to run out if memory (configurable through web.xml with param "MinimumFreeMemory")
 * * Database connection can be established.
 * * All OCSPSignTokens are aktive if not set as offline.
 * 
 * @author Philip Vendil
 * @version $Id: ExtOCSPHealthCheck.java,v 1.2 2006-07-24 10:48:51 anatom Exp $
 */

public class ExtOCSPHealthCheck implements IHealthCheck {
	
	private static Logger log = Logger.getLogger(ExtOCSPHealthCheck.class);

	private int minfreememory = 0;
	private String checkDBString = null;
	
	public void init(ServletConfig config) {
		minfreememory = Integer.parseInt(config.getInitParameter("MinimumFreeMemory")) * 1024 * 1024;
		checkDBString = config.getInitParameter("checkDBString");
	}

	
	public String checkHealth(HttpServletRequest request) {
		log.debug("Starting HealthCheck health check requested by : " + request.getRemoteAddr());
		String errormessage = "";
		
		errormessage += checkDB();
		if(errormessage.equals("")){
		  errormessage += checkMemory();								
		  errormessage += checkOCSPSignTokens();	
	
		}
		
		if(errormessage.equals("")){
			// everything seems ok.
			errormessage = null;
		}
		
		return errormessage;
	}
	
	private String checkMemory(){
		String retval = "";
        if(minfreememory >= Runtime.getRuntime().freeMemory()){
          retval = "\nError Virtual Memory is about to run out, currently free memory :" + Runtime.getRuntime().freeMemory();	
        }		
		
		return retval;
	}
	
	private String checkDB(){
		String retval = "";
		try{	
		  Connection con = JDBCUtil.getDBConnection(JNDINames.DATASOURCE);
		  Statement statement = con.createStatement();
		  statement.execute(checkDBString);		  
		  JDBCUtil.close(con);
		}catch(Exception e){
			retval = "\nError creating connection to EJBCA Database.";
			log.error("Error creating connection to EJBCA Database.",e);
		}
		return retval;
	}
	
	private String checkOCSPSignTokens(){
		String retval = "";
/*
 * Example code
 * 
 		Iterator iter = getCAAdminSession().getAvailableCAs(admin).iterator();
		while(iter.hasNext()){
			CAInfo cainfo = getCAAdminSession().getCAInfo(admin,((Integer) iter.next()).intValue());
			CATokenInfo tokeninfo = cainfo.getCATokenInfo(); 
			if(cainfo.getStatus() == SecConst.CA_ACTIVE){
			  if(tokeninfo instanceof HardCATokenInfo && ((HardCATokenInfo) tokeninfo).getCATokenStatus() == IHardCAToken.STATUS_OFFLINE){
				retval +="\n Error CA Token is disconnected, CA Name : " + cainfo.getName();
				log.error("Error CA Token is disconnected, CA Name : " + cainfo.getName());
			  }
			}
		}*/				
		return retval;
	}
		

}
