package eu.transkribus.client.connection;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.Future;

import javax.security.auth.login.LoginException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.HttpUrlConnectorProvider;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.message.GZipEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.transkribus.client.util.ClientRequestAuthFilter2;
import eu.transkribus.client.util.SessionExpiredException;
import eu.transkribus.core.model.beans.auth.TrpUserLogin;
import eu.transkribus.core.rest.RESTConst;

/**
 * Abstract TRP Server Connection class that encapsulates the Jersey Client boilerplate.
 * On instantiation, a session at the TRP Server is created via login.
 * @author philip
 *
 */
public abstract class ATrpServerConn implements Closeable {
	private final static Logger logger = LoggerFactory.getLogger(ATrpServerConn.class);
	
	private /*static*/ Client client;
	private /*static*/ TrpUserLogin login;
	private /*static*/ URI serverUri;
	protected /*static*/ WebTarget baseTarget;
	private /*static*/ WebTarget loginTarget;
	
	protected final static MediaType DEFAULT_RESP_TYPE = MediaType.APPLICATION_JSON_TYPE;
	
	public static String guiVersion="NA";
	
	protected ATrpServerConn(final String uriStr) throws LoginException {
		if (uriStr == null || uriStr.isEmpty()) {
			throw new LoginException("Server URI is not set!");
		}
		serverUri = UriBuilder.fromUri(uriStr).build();
		
		//FIXME it seems like there is some internal buffering.
		// how to get to this property?
		//httpUrlConnection.setChunkedStreamingMode(chunklength) - disables buffering and uses chunked transfer encoding to send request
		ClientConfig config = new ClientConfig();
		HttpUrlConnectorProvider prov = new HttpUrlConnectorProvider();
		prov.chunkSize(1024);
//		logger.debug("USE_FIXED_STREAMING_LENGTH = " + prov.USE_FIXED_LENGTH_STREAMING.toString());
		config.connectorProvider(prov);
		config.register(MultiPartFeature.class);
				
		client = ClientBuilder.newClient(config);
		client.register(new ClientRequestAuthFilter2(this));
		client.register(new ClientRequestFilter() {
			@Override public void filter(ClientRequestContext requestContext) throws IOException {
				List<Object> vers = new ArrayList<>(1);
				vers.add(ATrpServerConn.guiVersion);
				requestContext.getHeaders().put(RESTConst.GUI_VERSION_HEADER_KEY, vers);				
			}
		});
		
		initTargets();
//		initBaseTarget();	

//		//register auth filter with the jSessionId and update the WebTarget accordingly
//		client.register(new ClientRequestAuthFilter(login.getSessionId()));
	}
			
	protected boolean isSameServer(final String uriStr) {
		URI serverUriStr = UriBuilder.fromUri(uriStr).build();
		return serverUriStr.equals(serverUri);		
	}
	
//	protected ATrpServerConn(final String uriStr, final String user, final String pw) throws LoginException {
//		this(uriStr);
//		
//		if (user == null || user.isEmpty() || pw == null || pw.isEmpty()) {
//			throw new LoginException("Credentials must not be empty!");
//		}
//		
//		// LOGIN STUFF:
//		//authenticate and retrieve the session data
//		login = login(user, pw);
//		
//		//register auth filter with the jSessionId and update the WebTarget accordingly
//		client.register(new ClientRequestAuthFilter(login.getSessionId()));
//		initBaseTarget();
//	}
	
	private void initTargets() {
		loginTarget = client.target(serverUri).path(RESTConst.BASE_PATH).path(RESTConst.AUTH_PATH).path(RESTConst.LOGIN_PATH);
		baseTarget = client.target(serverUri).path(RESTConst.BASE_PATH);
		baseTarget.register(GZipEncoder.class);
	}
	
	// OLD: 
//	protected ATrpServerConn(final String uriStr, final String user, final String pw) throws LoginException {
//		if (user == null || user.isEmpty() || pw == null || pw.isEmpty()) {
//			throw new LoginException("Credentials must not be empty!");
//		}
//		if (uriStr == null || uriStr.isEmpty()) {
//			throw new LoginException("Server URI is not set!");
//		}
//		serverUri = UriBuilder.fromUri(uriStr).build();
//		
//		//FIXME it seems like there is some internal buffering.
//		// how to get to this property?
//		//httpUrlConnection.setChunkedStreamingMode(chunklength) - disables buffering and uses chunked transfer encoding to send request
//		ClientConfig config = new ClientConfig();
//		HttpUrlConnectorProvider prov = new HttpUrlConnectorProvider();
//		prov.chunkSize(1024);
////		logger.debug("USE_FIXED_STREAMING_LENGTH = " + prov.USE_FIXED_LENGTH_STREAMING.toString());
//		config.connectorProvider(prov);
//		config.register(MultiPartFeature.class);
//		client = ClientBuilder.newClient(config);
//		loginTarget = client.target(serverUri).path(RESTConst.BASE_PATH).path(RESTConst.AUTH_PATH).path(RESTConst.LOGIN_PATH);
//		//authenticate and retrieve the session data
//		login = login(user, pw);
//		//register auth filter with the jSessionId and update the WebTarget accordingly
//		client.register(new ClientRequestAuthFilter(login.getSessionId()));
//		baseTarget = client.target(serverUri).path(RESTConst.BASE_PATH);
//		//TODO test this:
//		baseTarget.register(GZipEncoder.class);
//	}
	// END OLD
		
	@Override
	public void close() {
		logout();
		client.close();
	}

	// called upon garbage collection:
	@Override protected void finalize() throws Throwable {
		close();
	};

	public TrpUserLogin login(final String user, final String pw) throws LoginException {
		if (login != null) {
			logout();
		}

		//post creds to /rest/auth/login
		Form form = new Form();
		form = form.param(RESTConst.USER_PARAM, user);
		form = form.param(RESTConst.PW_PARAM, pw);
		
		login = null;
		try {
			login = postEntityReturnObject(
					loginTarget, 
					form, MediaType.APPLICATION_FORM_URLENCODED_TYPE, 
					TrpUserLogin.class, MediaType.APPLICATION_JSON_TYPE
					);
			
			initTargets();
		} catch(Exception e) {
			login = null;
			logger.error("Login request failed!", e);
			throw new LoginException(e.getMessage());
		}
		logger.debug("Logged in as: " + login.toString());
		
		return login;
	}

	public void logout() throws ServerErrorException, ClientErrorException {
		try {
			final WebTarget target = baseTarget.path(RESTConst.AUTH_PATH).path(RESTConst.LOGOUT_PATH);
			//just post a null entity. SessionId is added in RequestAuthFilter
			Response resp = target.request().post(null);
			checkStatus(resp, target);
		} catch(SessionExpiredException see) {
			logger.info("Logout failed as session has expired or sessionId is invalid.");
		} finally {
			login = null;
//			client.close();
		}
	}
	
	public TrpUserLogin getUserLogin() {
		return login;
	}
	
	public String getServerUri() {
		return serverUri.toString();
	}
	
	protected <T> List<T> getList(WebTarget target, GenericType<List<T>> returnType) throws SessionExpiredException, ServerErrorException, ClientErrorException{
		Response resp = target.request(DEFAULT_RESP_TYPE).get();
		checkStatus(resp, target);
		final List<T> genericList = extractList(resp, returnType);
		return genericList;
	}
		
//	protected <T> List<T> getListAsync(WebTarget target, GenericType<List<T>> returnType, InvocationCallback<Response> callback) throws SessionExpiredException, ServerErrorException, ClientErrorException{
//		Future<Response> fut = target.request(DEFAULT_RESP_TYPE).async().get(callback);
//		
//		checkStatus(resp, target);
//		final List<T> genericList = extractList(resp, returnType);
//		return genericList;
//	}	
	
	protected <T> T getObject(WebTarget target, Class<T> clazz) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		return getObject(target, clazz, null);
	}
	
	protected <T> T getObject(WebTarget target, Class<T> clazz, MediaType type) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		if(type == null){
			type = DEFAULT_RESP_TYPE;
		}
		Response resp = target.request(type).get();
		checkStatus(resp, target);
		T object = extractObject(resp, clazz);
		return object;
	}
	
	protected void delete(WebTarget target) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		Response resp = target.request().delete();
		checkStatus(resp, target);
	}
	
	protected <T> void postEntity(WebTarget target, T entity, MediaType postMediaType) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		Response resp = target.request().post(Entity.entity(entity, postMediaType));
		checkStatus(resp, target);
	}
	
	protected <T> void postNull(WebTarget target) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		Response resp = target.request().post(null);
		checkStatus(resp, target);
	}
	
	protected <T, R> R postXmlEntityReturnObject(WebTarget target, T entity, Class<R> returnType) throws SessionExpiredException, ServerErrorException, ClientErrorException{
		return postEntityReturnObject(target, entity, MediaType.APPLICATION_XML_TYPE, returnType, DEFAULT_RESP_TYPE);
	}
	
	protected <T, R> R postEntityReturnObject(WebTarget target, T entity, MediaType postMediaType, Class<R> returnType, MediaType returnMediaType) throws SessionExpiredException, ServerErrorException, ClientErrorException {
		Entity<T> ent = buildEntity(entity, postMediaType);
		Response resp = target.request(returnMediaType).post(ent);
		checkStatus(resp, target);
		R object = extractObject(resp, returnType);
		return object;
	}
	
	/**
	 * Does not work with Tomcat 7 (yet). See server.rest.Layout.java
	 */
	//	protected <T, R> R asyncPostEntityReturnObject(WebTarget target, T entity, MediaType postMediaType, Class<R> returnType, MediaType returnMediaType) throws InterruptedException, ExecutionException, SessionExpiredException, ServerErrorException, ClientErrorException{
	//		final Entity<T> ent = buildEntity(entity, postMediaType);
	//		final AsyncInvoker asyncInvoker = target.request(returnMediaType).async();
	//		final Future<Response> responseFuture = asyncInvoker.post(ent);
	//		logger.debug("Request is being processed asynchronously.");
	//		final Response resp = responseFuture.get();
	//		// get() waits for the response to be ready
	//		checkStatus(resp, target);
	//		R object = extractObject(resp, returnType);
	//		logger.debug("Response received.");
	//		return object;
	//	}
	
	protected <T, R> List<R> postXmlEntityReturnList(WebTarget target, T entity, GenericType<List<R>> returnType) throws SessionExpiredException, ServerErrorException, ClientErrorException{
		return postEntityReturnList(target, entity, MediaType.APPLICATION_XML_TYPE, returnType, DEFAULT_RESP_TYPE);
	}
	
	protected <T, R> List<R> postEntityReturnList(WebTarget target, T entity, final MediaType postMediaType, GenericType<List<R>> returnType, MediaType returnMediaType) throws SessionExpiredException, ServerErrorException, ClientErrorException{
		Entity<T> ent = buildEntity(entity, postMediaType);
		Response resp = target.request(returnMediaType).post(ent);
		checkStatus(resp, target);
		List<R> genericList = extractList(resp, returnType);
		resp.close();
		return genericList;
	}	
	
	private <T> Entity<T> buildEntity(T entity, final MediaType postMediaType){
		Entity<T> ent = null;
		if (entity != null) {
			ent = Entity.entity(entity, postMediaType);
		}
		return ent;
	}
	
	private <T> T extractObject(Response resp, Class<T> returnType) {
		T object = null;
		try{
			object = resp.readEntity(returnType);
		} catch(ProcessingException | IllegalStateException e) {
			logger.error("Server response did not contain an object of type " + returnType.getSimpleName());
			throw e;
		} finally {
			resp.close();
		}
		return object;
	}

	<T> List<T> extractList(Response resp, GenericType<List<T>> returnType) throws ProcessingException {
		List<T> list = null;
		try{
			list = resp.readEntity(returnType);
		} catch(ProcessingException | IllegalStateException e) {
			logger.error("Server response did not contain a list of type " + returnType.getType());
			throw e;
		} finally {
			resp.close();
		}
		return list;
	}
	
	private String readStringEntity(Response resp) {
		try {
			return resp.readEntity(String.class);
		} catch (ProcessingException e) {
			return "";
		}
	}
	
	/**
	 * TODO Unauthorized 403 => Session expired OR User is not authorized for this action
	 */
	protected void checkStatus(Response resp, WebTarget target) throws SessionExpiredException, ClientErrorException, ServerErrorException {
		final int status = resp.getStatus();
				
		final String loc = target.getUri().toString();
		if(status < 300) {
			//logger.debug(loc + " - " + status + " OK");
			return;
		} else if(status == 400) {
			throw new ClientErrorException(loc + " - Bad Request (400). "+readStringEntity(resp), resp);
		} else if(status == 401) {
			if (login != null)
				throw new SessionExpiredException(loc + " - Login expired (401).", login);
			else
				throw new SessionExpiredException(loc + " - Not logged in (401).");
		} else if(status == 403) {
			throw new ClientErrorException(loc + " - Forbidden request. (403) "+readStringEntity(resp), resp);
		} else if(status == 404) {
			throw new ClientErrorException(loc + " - Bad parameters. No entity found. (404) "+readStringEntity(resp), resp);
		} else if(status == 405) {
			throw new ClientErrorException(loc + " - Method not allowed! (405) "+readStringEntity(resp), resp);
		} 
		else if (status < 500) {
			throw new ClientErrorException("Client error: "+readStringEntity(resp), resp);
		}
		else { // 500 etc.
			throw new ServerErrorException(loc + " - Some server error occured! " + status + " - " + resp.getStatusInfo(), status);
		}
	}
	
	public class ClientStatus extends Observable implements Observer {
		private static final String STATUS_IDLE = "IDLE";
		private static final String STATUS_BUSY = "BUSY";
		public String status = "IDLE";

		@Override
		public void update(Observable o, Object arg) {
			setChanged();
			if (arg instanceof String) {
	            status = (String) arg;
	        }
			notifyObservers(status);
		}
		
		public void setIdle(){
			setChanged();
			status = STATUS_IDLE;
			notifyObservers(status);
		}
		
		public void setBusy(){
			setChanged();
			status = STATUS_BUSY;
			notifyObservers(status);
		}
	}
}