/*
 * Copyright (C) 2005-2007 Alfresco Software Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

 * As a special exception to the terms and conditions of version 2.0 of 
 * the GPL, you may redistribute this Program in connection with Free/Libre 
 * and Open Source Software ("FLOSS") applications as described in Alfresco's 
 * FLOSS exception.  You should have recieved a copy of the text describing 
 * the FLOSS exception, and it is also available here: 
 * http://www.alfresco.com/legal/licensing
 */
package org.alfresco.repo.webdav.auth;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.Status;
import javax.transaction.UserTransaction;

import net.sf.acegisecurity.BadCredentialsException;

import org.alfresco.jlan.server.auth.PasswordEncryptor;
import org.alfresco.jlan.server.auth.ntlm.NTLM;
import org.alfresco.jlan.server.auth.ntlm.NTLMLogonDetails;
import org.alfresco.jlan.server.auth.ntlm.NTLMMessage;
import org.alfresco.jlan.server.auth.ntlm.NTLMv2Blob;
import org.alfresco.jlan.server.auth.ntlm.TargetInfo;
import org.alfresco.jlan.server.auth.ntlm.Type1NTLMMessage;
import org.alfresco.jlan.server.auth.ntlm.Type2NTLMMessage;
import org.alfresco.jlan.server.auth.ntlm.Type3NTLMMessage;
import org.alfresco.jlan.util.DataPacker;
import org.alfresco.repo.SessionUser;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.MD4PasswordEncoder;
import org.alfresco.repo.security.authentication.MD4PasswordEncoderImpl;
import org.alfresco.repo.security.authentication.NTLMMode;
import org.alfresco.repo.security.authentication.ntlm.NTLMPassthruToken;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;

/**
 * Base class with common code and initialisation for NTLM authentication filters.
 */
public abstract class BaseNTLMAuthenticationFilter extends BaseSSOAuthenticationFilter
{
    // NTLM authentication session object names
    public static final String NTLM_AUTH_SESSION = "_alfNTLMAuthSess";
    public static final String NTLM_AUTH_DETAILS = "_alfNTLMDetails";
    
    protected static final String WWW_AUTHENTICATE = "WWW-Authenticate";
    protected static final String AUTHORIZATION = "Authorization";
    protected static final String AUTH_NTLM = "NTLM";
    
    // NTLM flags mask for use with an authentication component that supports MD4 hashed password
    // Enable NTLMv1 and NTLMv2
    private static final int NTLM_FLAGS_NTLM2 = NTLM.Flag56Bit +
                                                NTLM.Flag128Bit +
                                                NTLM.FlagLanManKey +
                                                NTLM.FlagNegotiateNTLM +
                                                NTLM.FlagNTLM2Key +
                                                NTLM.FlagNegotiateUnicode;

    // NTLM flags mask for use with an authentication component that uses passthru auth
    // Enable NTLMv1 only
    private static final int NTLM_FLAGS_NTLM1 = NTLM.Flag56Bit +
                                                NTLM.FlagLanManKey +
                                                NTLM.FlagNegotiateNTLM +
                                                NTLM.FlagNegotiateOEM +
                                                NTLM.FlagNegotiateUnicode;
    
    // NTLM flags to send to the client with the allowed logon types
    private int m_ntlmFlags;
    
    // Password encryptor
    private PasswordEncryptor m_encryptor = new PasswordEncryptor();
    
    // Random number generator used to generate challenge keys
    private Random m_random = new Random(System.currentTimeMillis());
    
    // MD4 hash decoder
    private MD4PasswordEncoder m_md4Encoder = new MD4PasswordEncoderImpl();
    
    // Allow guest access
    private boolean m_allowGuest = false;
    
    // Allow guest access, map unknown users to the guest account
    private boolean m_mapUnknownUserToGuest = false;
    
    // Disable NTLMv2 support
    private boolean m_disableNTLMv2 = false;
    
    /**
     * Initialize the filter
     * 
     * @param args FilterConfig
     * 
     * @exception ServletException
     */
    public void init(FilterConfig args) throws ServletException
    {
    	// Call the base SSO filter initialization
    	
    	super.init( args);

    	// Check that the authentication component supports the required mode
    	
        if (m_authComponent.getNTLMMode() != NTLMMode.MD4_PROVIDER &&
            m_authComponent.getNTLMMode() != NTLMMode.PASS_THROUGH)
        {
            throw new ServletException("Required authentication mode not available");
        }
        
        // Check if guest access is to be allowed
        
        String guestAccess = args.getInitParameter("AllowGuest");
        if (guestAccess != null)
        {
            m_allowGuest = Boolean.parseBoolean(guestAccess);
            
            if (getLogger().isDebugEnabled() && m_allowGuest)
                getLogger().debug("NTLM filter guest access allowed");
        }
        
        // Check if unknown users should be mapped to guest access
        
        String mapUnknownToGuest = args.getInitParameter("MapUnknownUserToGuest");
        if (mapUnknownToGuest != null)
        {
            m_mapUnknownUserToGuest = Boolean.parseBoolean(mapUnknownToGuest);
            
            if (getLogger().isDebugEnabled() && m_mapUnknownUserToGuest)
                getLogger().debug("NTLM filter map unknown users to guest");
        }
        
        // Set the NTLM flags depending on the authentication component supporting MD4 passwords,
        // or is using passthru auth
        
        if (m_authComponent.getNTLMMode() == NTLMMode.MD4_PROVIDER && m_disableNTLMv2 == false)
        {
            // Allow the client to use an NTLMv2 logon
        	
            m_ntlmFlags = NTLM_FLAGS_NTLM2;
        }
        else
        {
            // Only allows NTLMv1 type logons as passthru authentication is being used
        	
            m_ntlmFlags = NTLM_FLAGS_NTLM1;
        }
    }
    
    /**
     * Run the filter
     * 
     * @param sreq ServletRequest
     * @param sresp ServletResponse
     * @param chain FilterChain
     * @exception IOException
     * @exception ServletException
     */
    public void doFilter(ServletRequest sreq, ServletResponse sresp, FilterChain chain) throws IOException,
            ServletException
    {
        // Get the HTTP request/response/session
        HttpServletRequest req = (HttpServletRequest) sreq;
        HttpServletResponse resp = (HttpServletResponse) sresp;
        HttpSession httpSess = req.getSession(true);
        
        // If a filter up the chain has marked the request as not requiring auth then respect it
        
        if (req.getAttribute( NO_AUTH_REQUIRED) != null)
        {
            if ( getLogger().isDebugEnabled())
                getLogger().debug("Authentication not required (filter), chaining ...");
            
            // Chain to the next filter
            chain.doFilter(sreq, sresp);
            return;
        }
        
        // Check if there is an authorization header with an NTLM security blob
        String authHdr = req.getHeader(AUTHORIZATION);
        boolean reqAuth = false;
        
        // Check if an NTLM authorization header was received
        
        if ( authHdr != null)
        {
        	// Check for an NTLM authorization header
        	
        	if ( authHdr.startsWith(AUTH_NTLM))
        		reqAuth = true;
        	else if ( authHdr.startsWith( "Negotiate"))
        	{
        		if ( getLogger().isDebugEnabled())
        			getLogger().debug("Received 'Negotiate' from client, may be SPNEGO/Kerberos logon");
        		
        		// Restart the authentication
        		
            	restartLoginChallenge(resp, httpSess);
        		return;
        	}
        }
        
        // Check if the user is already authenticated
        SessionUser user = getSessionUser( httpSess);
        
        if (user != null && reqAuth == false)
        {
            try
            {
                if (getLogger().isDebugEnabled())
                    getLogger().debug("User " + user.getUserName() + " validate ticket");
                
                // Validate the user ticket
                m_authService.validate(user.getTicket());
                reqAuth = false;
                
                // Filter validate hook
                onValidate( req, httpSess);
            }
            catch (AuthenticationException ex)
            {
                if (getLogger().isErrorEnabled())
                    getLogger().error("Failed to validate user " + user.getUserName(), ex);
                
                removeSessionUser( httpSess);
                
                reqAuth = true;
            }
        }

        // If the user has been validated and we do not require re-authentication then continue to
        // the next filter
        if (reqAuth == false && user != null)
        {
            if (getLogger().isDebugEnabled())
                getLogger().debug("Authentication not required (user), chaining ...");
            
            // Chain to the next filter
            chain.doFilter(sreq, sresp);
            return;
        }

        // Check if the login page is being accessed, do not intercept the login page
        if (hasLoginPage() && req.getRequestURI().endsWith(getLoginPage()) == true)
        {
            if (getLogger().isDebugEnabled())
                getLogger().debug("Login page requested, chaining ...");
            
            // Chain to the next filter
            chain.doFilter( sreq, sresp);
            return;
        }
        
        // Check if the browser is Opera, if so then display the login page as Opera does not
        // support NTLM and displays an error page if a request to use NTLM is sent to it
        String userAgent = req.getHeader("user-agent");
        if (userAgent != null && userAgent.indexOf("Opera ") != -1)
        {
            if (getLogger().isDebugEnabled())
                getLogger().debug("Opera detected, redirecting to login page");

            // If there is no login page configured (WebDAV) then just keep requesting the user details from the client
            
            if ( hasLoginPage())
            	redirectToLoginPage(req, resp);
            else
            	restartLoginChallenge(resp, httpSess);
            return;
        }
        
        // Check the authorization header
        if (authHdr == null)
        {
            // Check for a ticket based logon, if enabled
            
            if ( allowsTicketLogons())
            {
            	// Check if the request includes an authentication ticket
            	
            	if ( checkForTicketParameter(req, httpSess)) {
            		
        		    // Authentication was bypassed using a ticket parameter
            		
        		    chain.doFilter(sreq, sresp);
        		    return;
            	}
            }
            
            // DEBUG
            	
            if (getLogger().isDebugEnabled())
                getLogger().debug("New NTLM auth request from " + req.getRemoteHost() + " (" +
                        req.getRemoteAddr() + ":" + req.getRemotePort() + ")");
                
            // Send back a request for NTLM authentication
            restartLoginChallenge(resp, httpSess);
        }
        else
        {
            // Decode the received NTLM blob and validate
            final byte[] ntlmByts = Base64.decodeBase64(authHdr.substring(5).getBytes());
            int ntlmTyp = NTLMMessage.isNTLMType(ntlmByts);
            if (ntlmTyp == NTLM.Type1)
            {
                // Process the type 1 NTLM message
                Type1NTLMMessage type1Msg = new Type1NTLMMessage(ntlmByts);
                processType1(type1Msg, req, resp, httpSess);
            }
            else if (ntlmTyp == NTLM.Type3)
            {
                // Process the type 3 NTLM message
                Type3NTLMMessage type3Msg = new Type3NTLMMessage(ntlmByts);
                processType3(type3Msg, req, resp, httpSess, chain);
            }
            else
            {
                if (getLogger().isDebugEnabled())
                    getLogger().debug("NTLM blob not handled, redirecting to login page.");
                
                redirectToLoginPage(req, resp);
            }
        }
    }

    /**
     * Delete the servlet filter
     */
    public void destroy()
    {
    }
    
    /**
     * Process a type 1 NTLM message
     * 
     * @param type1Msg Type1NTLMMessage
     * @param req HttpServletRequest
     * @param res HttpServletResponse
     * @param session HttpSession
     * @exception IOException
     */
    protected void processType1(Type1NTLMMessage type1Msg, HttpServletRequest req,
            HttpServletResponse res, HttpSession session) throws IOException
    {
        Log logger = getLogger();
        
        if (getLogger().isDebugEnabled())
            getLogger().debug("Received type1 " + type1Msg);
        
        // Get the existing NTLM details
        NTLMLogonDetails ntlmDetails = null;
        
        if (session != null)
        {
            ntlmDetails = (NTLMLogonDetails)session.getAttribute(NTLM_AUTH_DETAILS);
        }
        
        // Check if cached logon details are available
        if (ntlmDetails != null && ntlmDetails.hasType2Message() &&
            ntlmDetails.hasNTLMHashedPassword() && ntlmDetails.hasAuthenticationToken())
        {
            // Get the authentication server type2 response
            Type2NTLMMessage cachedType2 = ntlmDetails.getType2Message();
            
            byte[] type2Bytes = cachedType2.getBytes();
            String ntlmBlob = "NTLM " + new String(Base64.encodeBase64(type2Bytes));
            
            if (getLogger().isDebugEnabled())
                getLogger().debug("Sending cached NTLM type2 to client - " + cachedType2);
            
            // Send back a request for NTLM authentication
            res.setHeader(WWW_AUTHENTICATE, ntlmBlob);
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.flushBuffer();
        }
        else
        {
            // Clear any cached logon details
            session.removeAttribute(NTLM_AUTH_DETAILS);
            
            // Set the 8 byte challenge for the new logon request
            byte[] challenge = null;
            NTLMPassthruToken authToken = null;
            
            if (m_authComponent.getNTLMMode() == NTLMMode.MD4_PROVIDER)
            {
                // Generate a random 8 byte challenge
            	
                challenge = new byte[8];
                DataPacker.putIntelLong(m_random.nextLong(), challenge, 0);
            }
            else
            {
                // Get the client domain
                String domain = type1Msg.getDomain();
                if (domain == null || domain.length() == 0)
                {
                    domain = mapClientAddressToDomain(req.getRemoteAddr());
                }
                
                if (getLogger().isDebugEnabled())
                    getLogger().debug("Client domain " + domain);
                
                // Create an authentication token for the new logon
                authToken = new NTLMPassthruToken(domain);
                
                // Run the first stage of the passthru authentication to get the challenge
                m_authComponent.authenticate(authToken);
                
                // Get the challenge from the token
                if (authToken.getChallenge() != null)
                {
                    challenge = authToken.getChallenge().getBytes();
                }
            }
            
            // Get the flags from the client request and mask out unsupported features
            int ntlmFlags = type1Msg.getFlags() & m_ntlmFlags;
            
            // Build a type2 message to send back to the client, containing the challenge
            List<TargetInfo> tList = new ArrayList<TargetInfo>();
            tList.add(new TargetInfo(NTLM.TargetServer, m_srvName));
            
            Type2NTLMMessage type2Msg = new Type2NTLMMessage();
            type2Msg.buildType2(ntlmFlags, m_srvName, challenge, null, tList);
            
            // Store the NTLM logon details, cache the type2 message, and token if using passthru
            ntlmDetails = new NTLMLogonDetails();
            ntlmDetails.setType2Message(type2Msg);
            ntlmDetails.setAuthenticationToken(authToken);
            
            session.setAttribute(NTLM_AUTH_DETAILS, ntlmDetails);
            
            if (getLogger().isDebugEnabled())
                getLogger().debug("Sending NTLM type2 to client - " + type2Msg);
            
            // Send back a request for NTLM authentication
            byte[] type2Bytes = type2Msg.getBytes();
            String ntlmBlob = "NTLM " + new String(Base64.encodeBase64(type2Bytes));

            res.setHeader(WWW_AUTHENTICATE, ntlmBlob);
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.flushBuffer();
        }
    }
    
    /**
     * Process a type 3 NTLM message
     * 
     * @param type3Msg Type3NTLMMessage
     * @param req HttpServletRequest
     * @param res HttpServletResponse
     * @param session HttpSession
     * @param chain FilterChain
     * 
     * @exception IOException
     * @exception ServletException
     */
    protected void processType3(Type3NTLMMessage type3Msg, HttpServletRequest req, HttpServletResponse res,
            HttpSession session, FilterChain chain) throws IOException, ServletException
    {
        Log logger = getLogger();
        
        if (logger.isDebugEnabled())
            logger.debug("Received type3 " + type3Msg);
        
        // Get the existing NTLM details
        NTLMLogonDetails ntlmDetails = null;
        SessionUser user = null;
        
        if (session != null)
        {
            ntlmDetails = (NTLMLogonDetails)session.getAttribute(NTLM_AUTH_DETAILS);
            user = getSessionUser(session);
        }
        
        // Get the NTLM logon details
        String userName = type3Msg.getUserName();
        String workstation = type3Msg.getWorkstation();
        String domain = type3Msg.getDomain();
        
        boolean authenticated = false;
        
        // Check if we are using cached details for the authentication
        if (user != null && ntlmDetails != null && ntlmDetails.hasNTLMHashedPassword())
        {
            // Check if the received NTLM hashed password matches the cached password
            byte[] ntlmPwd = type3Msg.getNTLMHash();
            byte[] cachedPwd = ntlmDetails.getNTLMHashedPassword();
            
            if (ntlmPwd != null)
            {
            	authenticated = Arrays.equals( cachedPwd, ntlmPwd);
            }
            
            if (logger.isDebugEnabled())
                logger.debug("Using cached NTLM hash, authenticated = " + authenticated);
            
            try
            {
                if (logger.isDebugEnabled())
                    logger.debug("User " + user.getUserName() + " validate ticket");
                
                // Validate the user ticket
                m_authService.validate(user.getTicket());
                
                onValidate(req, session);
            }
            catch (AuthenticationException ex)
            {
                if (logger.isErrorEnabled())
                    logger.error("Failed to validate user " + user.getUserName(), ex);
                
                removeSessionUser(session);
                
                onValidateFailed(req, res, session);
                return;
            }
            
            // Allow the user to access the requested page
            chain.doFilter(req, res);
            return;
        }
        else
        {
            // Check if we are using local MD4 password hashes or passthru authentication
            if (m_authComponent.getNTLMMode() == NTLMMode.MD4_PROVIDER)
            {
                // Check if guest logons are allowed and this is a guest logon
                if (m_allowGuest && userName.equalsIgnoreCase(m_authComponent.getGuestUserName()))
                {
                    // Indicate that the user has been authenticated
                    authenticated = true;
                    
                    if (getLogger().isDebugEnabled())
                        getLogger().debug("Guest logon");
                }
                else
                {
                    // Get the stored MD4 hashed password for the user, or null if the user does not exist
                    String md4hash = getMD4Hash(userName);
                    
                    if (md4hash != null)
                    {
                        authenticated = validateLocalHashedPassword(type3Msg, ntlmDetails, authenticated, md4hash);
                    }
                    else
                    {
                        // Check if unknown users should be logged on as guest
                        if (m_mapUnknownUserToGuest)
                        {
                            // Reset the user name to be the guest user
                            userName = m_authComponent.getGuestUserName();
                            authenticated = true;
                            
                            if (logger.isDebugEnabled())
                                logger.debug("User " + userName + " logged on as guest, no Alfresco account");
                        }
                        else
                        {
                            if (logger.isDebugEnabled())
                                logger.debug("User " + userName + " does not have Alfresco account");
                            
                            // Bypass NTLM authentication and display the logon screen,
                            // as user account does not exist in Alfresco
                            authenticated = false;
                        }
                    }
                }
            }
            else
            {
                //  Determine if the client sent us NTLMv1 or NTLMv2
                if (type3Msg.hasFlag(NTLM.Flag128Bit) && type3Msg.hasFlag(NTLM.FlagNTLM2Key) ||
                    (type3Msg.getNTLMHash() != null && type3Msg.getNTLMHash().length > 24))
                {
                    // Cannot accept NTLMv2 if we are using passthru auth
                    if (logger.isErrorEnabled())
                        logger.error("Client " + workstation + " using NTLMv2 logon, not valid with passthru authentication");
                }
                else
                {
                    // Passthru mode, send the hashed password details to the passthru authentication server
                    NTLMPassthruToken authToken = (NTLMPassthruToken) ntlmDetails.getAuthenticationToken();
                    authToken.setUserAndPassword(type3Msg.getUserName(), type3Msg.getNTLMHash(), PasswordEncryptor.NTLM1);
                    
                    try
                    {
                        // Run the second stage of the passthru authentication
                        m_authComponent.authenticate(authToken);
                        authenticated = true;
                        
                        // Check if the user has been logged on as guest
                        if (authToken.isGuestLogon())
                        {
                            userName = m_authComponent.getGuestUserName();
                        }
                        
                        // Set the authentication context
                        m_authComponent.setCurrentUser(userName);
                    }
                    catch (BadCredentialsException ex)
                    {
                        if (logger.isDebugEnabled())
                            logger.debug("Authentication failed, " + ex.getMessage());
                    }
                    catch (AuthenticationException ex)
                    {
                        if (logger.isDebugEnabled())
                            logger.debug("Authentication failed, " + ex.getMessage());
                    }
                    finally
                    {
                        // Clear the authentication token from the NTLM details
                        ntlmDetails.setAuthenticationToken(null);
                    }
                }
            }
            
            // Check if the user has been authenticated, if so then setup the user environment
            if (authenticated == true)
            {
                if (user == null)
                {
                    user = createUserEnvironment(session, userName);
                }
                else
                {
                    // user already exists - revalidate ticket to authenticate the current user thread
                    try
                    {
                        m_authService.validate(user.getTicket());
                    }
                    catch (AuthenticationException ex)
                    {
                        if (logger.isErrorEnabled())
                            logger.error("Failed to validate user " + user.getUserName(), ex);
                        
                        removeSessionUser(session);
                        
                        onValidateFailed(req, res, session);
                        return;
                    }
                }
                
                onValidate(req, session);
                
                // Update the NTLM logon details in the session
                if (ntlmDetails == null)
                {
                    // No cached NTLM details
                    ntlmDetails = new NTLMLogonDetails(userName, workstation, domain, false, m_srvName);
                    ntlmDetails.setNTLMHashedPassword(type3Msg.getNTLMHash());
                    session.setAttribute(NTLM_AUTH_DETAILS, ntlmDetails);
                    
                    if (logger.isDebugEnabled())
                        logger.debug("No cached NTLM details, created");
                }
                else
                {
                    // Update the cached NTLM details
                    ntlmDetails.setDetails(userName, workstation, domain, false, m_srvName);
                    ntlmDetails.setNTLMHashedPassword(type3Msg.getNTLMHash());

                    if (logger.isDebugEnabled())
                        logger.debug("Updated cached NTLM details");
                }
                
                if (logger.isDebugEnabled())
                    logger.debug("User logged on via NTLM, " + ntlmDetails);
                
                if (onLoginComplete(req, res))
                {
                    // Allow the user to access the requested page
                    chain.doFilter(req, res);
                }
            }
            else
            {
                restartLoginChallenge(res, session);
            }
        }
    }
    
    /**
     * Validate the MD4 hash against local password
     * 
     * @param type3Msg
     * @param ntlmDetails
     * @param authenticated
     * @param md4hash
     * 
     * @return true if password hash is valid, false otherwise
     */
    protected boolean validateLocalHashedPassword(Type3NTLMMessage type3Msg, NTLMLogonDetails ntlmDetails, boolean authenticated, String md4hash)
    {
    	// Make sure we have hte cached NTLM details, including the type2 message with the server challenge
    	
    	if ( ntlmDetails == null || ntlmDetails.getType2Message() == null)
    	{
    		// DEBUG
    		
    		if ( getLogger().isDebugEnabled())
    			getLogger().debug("No cached Type2, ntlmDetails=" + ntlmDetails);
    		
    		// Not authenticated
    		
    		return false;
    	}
    	
        //  Determine if the client sent us NTLMv1 or NTLMv2
        if (type3Msg.hasFlag(NTLM.FlagNTLM2Key))
        {
            //  Determine if the client sent us an NTLMv2 blob or an NTLMv2 session key
            if (type3Msg.getNTLMHashLength() > 24)
            {
                //  Looks like an NTLMv2 blob
                authenticated = checkNTLMv2(md4hash, ntlmDetails.getChallengeKey(), type3Msg);
                
                if (getLogger().isDebugEnabled())
                    getLogger().debug((authenticated ? "Logged on" : "Logon failed") + " using NTLMSSP/NTLMv2");
                
                // If the NTlmv2 autentication failed then check if the client has sent an NTLMv1 hash
                
                if ( authenticated == false && type3Msg.hasFlag(NTLM.Flag56Bit) && type3Msg.getLMHashLength() == 24)
                {
            		// Check the LM hash field
            		
            		authenticated = checkNTLMv1(md4hash, ntlmDetails.getChallengeKey(), type3Msg, true);

            		// DEBUG
            		
                    if (getLogger().isDebugEnabled())
                        getLogger().debug((authenticated ? "Logged on" : "Logon failed") + " using NTLMSSP/NTLMv1 (via fallback)");
                }
            }
            else
            {
                //  Looks like an NTLMv2 session key
                authenticated = checkNTLMv2SessionKey(md4hash, ntlmDetails.getChallengeKey(), type3Msg);
                
                if (getLogger().isDebugEnabled())
                    getLogger().debug((authenticated ? "Logged on" : "Logon failed") + " using NTLMSSP/NTLMv2SessKey");
            }
        }
        else
        {
            //  Looks like an NTLMv1 blob
            authenticated = checkNTLMv1(md4hash, ntlmDetails.getChallengeKey(), type3Msg, false);
            
            if (getLogger().isDebugEnabled())
                getLogger().debug((authenticated ? "Logged on" : "Logon failed") + " using NTLMSSP/NTLMv1");
        }
        return authenticated;
    }
    
    /**
     * Perform an NTLMv1 hashed password check
     * 
     * @param String md4hash
     * @param byte[] challenge
     * @param Type3NTLMMessage type3Msg
     * @param checkLMHash boolean
     * @return boolean
     */
    protected final boolean checkNTLMv1(String md4hash, byte[] challenge, Type3NTLMMessage type3Msg, boolean checkLMHash)
    {
        // Generate the local encrypted password using the challenge that was sent to the client
        byte[] p21 = new byte[21];
        byte[] md4byts = m_md4Encoder.decodeHash(md4hash);
        System.arraycopy(md4byts, 0, p21, 0, 16);

        // Generate the local hash of the password using the same challenge
        byte[] localHash = null;

        try
        {
            localHash = m_encryptor.doNTLM1Encryption(p21, challenge);
        }
        catch (NoSuchAlgorithmException ex)
        {
        }

        // Validate the password
        byte[] clientHash = checkLMHash ? type3Msg.getLMHash() : type3Msg.getNTLMHash();

        if (clientHash != null && localHash != null && clientHash.length == localHash.length)
        {
            int i = 0;

            while (i < clientHash.length && clientHash[i] == localHash[i])
            {
                i++;
            }

            if (i == clientHash.length)
            {
                // Hashed passwords match
                return true;
            }
        }

        // Hashed passwords do not match
        return false;
    }

    /**
     * Perform an NTLMv2 check
     * 
     * @param String md4hash
     * @param byte[] challenge
     * @param Type3NTLMMessage type3Msg
     * @return boolean
     */
    protected final boolean checkNTLMv2(String md4hash, byte[] challenge, Type3NTLMMessage type3Msg)
    {
    	boolean ntlmv2OK = false;
    	boolean lmv2OK   = false;
    	
        try
        {
            // Generate the v2 hash using the challenge that was sent to the client
            byte[] v2hash = m_encryptor.doNTLM2Encryption(m_md4Encoder.decodeHash(md4hash), type3Msg.getUserName(), type3Msg.getDomain());

            // Get the NTLMv2 blob sent by the client and the challenge that was sent by the server
            NTLMv2Blob v2blob = new NTLMv2Blob(type3Msg.getNTLMHash());

            // Calculate the HMAC of the received blob and compare
            byte[] srvHmac = v2blob.calculateHMAC(challenge, v2hash);
            byte[] clientHmac = v2blob.getHMAC();

            if (clientHmac != null && srvHmac != null && clientHmac.length == srvHmac.length)
            {
                int i = 0;

                while (i < clientHmac.length && clientHmac[i] == srvHmac[i])
                {
                    i++;
                }

                if (i == clientHmac.length)
                {
                    //  HMAC matches the client, user authenticated
                	
                	ntlmv2OK = true;
                }
            }
            
            // NTLMv2 check failed, try the LMv2 value

            if ( ntlmv2OK == false)
            {
	        	byte[] lmv2 = type3Msg.getLMHash();
	        	byte[] clChallenge = v2blob.getClientChallenge();
	        	
	            if ( lmv2 != null && lmv2.length == 24 && clChallenge != null && clChallenge.length == 8)
	            {
	            	// Check that the LM hash contains the client challenge as the last 8 bytes
	            	
	            	int i = 0;
	            	
	            	while ( i < clChallenge.length && lmv2[ i + 16] == clChallenge[ i])
	            		i++;
	            	
	            	if ( i == clChallenge.length)
	            	{
	            		// Calculate the LMv2 value
	            		
	            		byte[] lmv2Hmac = v2blob.calculateLMv2HMAC(v2hash, challenge, clChallenge);
	            		
	            		// Check if the LMv2 HMAC matches
	            		
	                    i = 0;
	
	                    while (i < lmv2Hmac.length && lmv2[i] == lmv2Hmac[i])
	                        i++;
	
	                    if (i == lmv2Hmac.length)
	                    {
	                        //  LMv2 HMAC matches the client, user authenticated
	                    	
	                        //return true;
	                    	lmv2OK = true;
	                    }
	            		
	            	}
	            }
            }
        }
        catch (Exception ex)
        {
            if (getLogger().isDebugEnabled())
                getLogger().debug(ex);
        }

        // Check if either of the NTLMv2 checks passed
        
        if ( ntlmv2OK || lmv2OK)
        	return true;
        return false;
    }

    /**
     * Perform an NTLMv2 session key check
     * 
     * @param String md4hash
     * @param byte[] challenge
     * @param Type3NTLMMessage type3Msg
     * @return boolean
     */
    protected final boolean checkNTLMv2SessionKey(String md4hash, byte[] challenge, Type3NTLMMessage type3Msg)
    {
        // Create the value to be encrypted by appending the server challenge and client challenge
        // and applying an MD5 digest
        byte[] nonce = new byte[16];
        System.arraycopy(challenge, 0, nonce, 0, 8);
        System.arraycopy(type3Msg.getLMHash(), 0, nonce, 8, 8);

        MessageDigest md5 = null;
        byte[] v2challenge = new byte[8];

        try
        {
            //  Create the MD5 digest
            md5 = MessageDigest.getInstance("MD5");

            //  Apply the MD5 digest to the nonce
            md5.update(nonce);
            byte[] md5nonce = md5.digest();

            //  We only want the first 8 bytes
            System.arraycopy(md5nonce, 0, v2challenge, 0, 8);
        }
        catch (NoSuchAlgorithmException ex)
        {
            // Log the error
            getLogger().error(ex);
        }

        // Generate the local encrypted password using the MD5 generated challenge
        byte[] p21 = new byte[21];
        byte[] md4byts = m_md4Encoder.decodeHash(md4hash);
        System.arraycopy(md4byts, 0, p21, 0, 16);

        // Generate the local hash of the password
        byte[] localHash = null;

        try
        {
            localHash = m_encryptor.doNTLM1Encryption(p21, v2challenge);
        }
        catch (NoSuchAlgorithmException ex)
        {
            // Log the error
            getLogger().error(ex);
        }

        // Validate the password
        byte[] clientHash = type3Msg.getNTLMHash();

        if (clientHash != null && localHash != null && clientHash.length == localHash.length)
        {
            int i = 0;

            while (i < clientHash.length && clientHash[i] == localHash[i])
            {
                i++;
            }

            if (i == clientHash.length)
            {
                //  Hashed password check successful
                return true;
            }
        }

        // Password check failed
        return false;
    }
    
    /**
     * Get the stored MD4 hashed password for the user, or null if the user does not exist
     * 
     * @param userName
     * @param md4hash
     * 
     * @return MD4 hash or null
     */
    protected String getMD4Hash(String userName)
    {
        String md4hash = null;
        
        // Wrap the auth component calls in a transaction
        UserTransaction tx = m_transactionService.getUserTransaction();
        try
        {
            tx.begin();
            
            // Get the stored MD4 hashed password for the user, or null if the user does not exist
            md4hash = m_authComponent.getMD4HashedPassword(userName);
        }
        catch (Throwable ex)
        {
            if (getLogger().isDebugEnabled())
                getLogger().debug(ex);
        }
        finally
        {
            // Rollback/commit the transaction if still valid
            if (tx != null)
            {
                try
                {
                    // Commit or rollback the transaction
                    if (tx.getStatus() == Status.STATUS_MARKED_ROLLBACK ||
                        tx.getStatus() == Status.STATUS_ROLLEDBACK ||
                        tx.getStatus() == Status.STATUS_ROLLING_BACK)
                    {
                        // Transaction is marked for rollback
                        tx.rollback();
                    }
                    else
                    {
                        // Commit the transaction
                        tx.commit();
                    }
                }
                catch (Throwable ex)
                {
                    if (getLogger().isDebugEnabled())
                        getLogger().debug(ex);
                }
            }
        }
        
        return md4hash;
    }
    
    /**
     * Restart the NTLM logon process
     * 
     * @param resp
     * @param httpSess
     * @throws IOException
     */
    protected void restartLoginChallenge(HttpServletResponse res, HttpSession session) throws IOException
    {
        // Remove any existing session and NTLM details from the session
        session.removeAttribute(NTLM_AUTH_SESSION);
        session.removeAttribute(NTLM_AUTH_DETAILS);
        
        // Force the logon to start again
        res.setHeader(WWW_AUTHENTICATE, AUTH_NTLM);
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.flushBuffer();
    }
    
    /**
     * Disable NTLMv2 support, must be called from the implementation constructor
     */
    protected final void disableNTLMv2()
    {
    	m_disableNTLMv2 = true;
    }
}
