package org.eclipse.jetty;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import lombok.extern.log4j.Log4j;

import org.eclipse.jetty.nosql.NoSqlSession;
import org.eclipse.jetty.nosql.NoSqlSessionManager;
import org.eclipse.jetty.server.session.AbstractSession;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@Log4j
public class RedisSessionManager extends NoSqlSessionManager {

    private final JedisPool     jedisPool;
    private final static String METADATA_SUFFIX             = "-metadata";
    // 1 hour of expiry time for sessions
    private final int           SESSION_EXPIRY_TIME_SECONDS = 60 * 60;
    private final Gson          gson                        = new GsonBuilder().create();

//    public RedisSessionManager(JedisPool jedisPool) {
//        this.jedisPool = jedisPool;
//        // 60 seconds is the stale period so that we don't refresh the object
//        // too often for the same request.
//        setStalePeriod(60);
//        // It is important to control when the save() for session happens
//        // refer to getSavePeriod method in NoSqlSessionManager for the details
//        // on the values of this parameter.
//
//        // a save period of 1 means the session is written to the DB whenever
//        // the active request count goes from 1 to 0 and the session is dirty.
//        setSavePeriod(1);
//    }

    public RedisSessionManager() {
        this.jedisPool = new JedisPool("redis", 6379);
    }

    public static class RedisNoSqlSession extends NoSqlSession {

        public RedisNoSqlSession() {
            super(null, null);
        }

        public RedisNoSqlSession(NoSqlSessionManager manager,
                                 HttpServletRequest request) {
            super(manager, request);
        }

        public RedisNoSqlSession(NoSqlSessionManager manager, long created,
                                 long accessed, String clusterId, Object version) {
            super(manager, created, accessed, clusterId, version);
        }

        public Map<String, Object> getAttributeMap() {
            return super.getAttributeMap();
        }

        public void loadAttributeMap(Map<String, Object> newMap) {
            Map<String, Object> oldMap = getAttributeMap();
            oldMap.clear();
            oldMap.putAll(newMap);
        }
    }

    @Override
    protected AbstractSession newSession(HttpServletRequest request) {
        return new RedisNoSqlSession(this, request);
    }

    @Override
    protected NoSqlSession loadSession(String clusterId) {
        // get the session from jedis.
        Jedis jedis = null;
        Metadata metadata = null;
        byte[] value = null;
        try {
            System.out.println("getting key for: " + clusterId);
            jedis = this.jedisPool.getResource();
            metadata = getSessionVersionAndLastSaveTimeFromRedis(clusterId);
            value = jedis.get(clusterId.getBytes());
        } catch (Exception e) {
            System.out.println("Exception loading session from redis, this will return null " +
                      e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }

        if (metadata == null || value == null) {
            return null;
        } else if (metadata.version == -1) {
            return null;
        } else {
            long created = System.currentTimeMillis();
            // create a new session object and assign it the map as well as the
            // version retrieved from Redis
            RedisNoSqlSession session = new RedisNoSqlSession(this, created,
                                                              created,
                                                              clusterId,
                                                              metadata.version);
            // Deserialize the map for session object
            Map<String, Object> attributeMap = (Map<String, Object>) deserialize(value);
            session.loadAttributeMap(attributeMap);
            return session;
        }
    }

    @Override
    protected Object save(NoSqlSession session, Object version,
                          boolean activateAfterSave) {
        final String sessionId = session.getId();
        final String sessionMetadataId = sessionId + METADATA_SUFFIX;
        final long newVersion = (version == null) ? 1 : ((Long) version + 1);
        // If session has been invalidated then set the flags to indicate to
        // other servers about the invalidation.
        final String metadata = session.isValid() ? (newVersion + "," + session.getLastAccessedTime())
                                                 : "-1,-1";
        final Map<String, Object> attrMap = session.isValid() ? ((RedisNoSqlSession) session).getAttributeMap()
                                                             : new HashMap<String, Object>();
        Jedis jedis = null;
        try {
            // save the session
            System.out.println("saving for key: " + sessionId + " with version:"
                      + newVersion);
            jedis = jedisPool.getResource();
            // Even in case of invalidate i.e. activateAfterSave=false, empty
            // session will be saved.
            Pipeline pipeline = jedis.pipelined();
            byte[] value = serialize(attrMap);
            pipeline.set(sessionId.getBytes(), value);
            pipeline.set(sessionMetadataId, metadata);
            // session will expire in 15 minutes if not touched. if it is
            // accessed then its lifetime is further extended.
            pipeline.expire(sessionId, SESSION_EXPIRY_TIME_SECONDS);
            // this will execute all the commands at once.
            pipeline.sync();
        } catch (Exception e) {
            //
            System.out.println("couldn't save the session to redis db, "
                              + "this will run into problems as session information "
 			      + "won't be available on other machines" +
                      e);
            return version;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        // Activate the session after save, this triggers an event which is
        // listened to by any configured listeners.
        if (activateAfterSave) {
            session.didActivate();
        }
        return newVersion;
    }

    @Override
    protected Object refresh(NoSqlSession session, Object version) {
        System.out.println("refresh called with session: " + session.getId()
                  + " and version:" + version);
        // check version of remote session
        Metadata metadata = getSessionVersionAndLastSaveTimeFromRedis(session);
        if (metadata == null
            || ((Long) metadata.version).compareTo((Long) version) <= 0) {
            if (metadata.version == -1) {
                // session was deleted.
                session.invalidate();
                return null;
            } else {
                return version;
            }
        }
        // remote version is greater than current version, load the remote
        // session & update current session with remote session.
        session.willPassivate();
        Set<String> currentSessionAttributeNames = session.getNames();
        NoSqlSession newSession = loadSession(session.getId());
        Set<String> newSessionAttributeNames = newSession.getNames();
        String newSessionAttributeName = null;
        Object newSessionAttributeValue = null;
        //
        for (Iterator<String> iter = newSessionAttributeNames.iterator(); iter.hasNext();) {
            newSessionAttributeName = iter.next();
            newSessionAttributeValue = newSession.getAttribute(newSessionAttributeName);
            // only bind value if it didn't exist in session
            if (!currentSessionAttributeNames.contains(newSessionAttributeName)) {
                session.doPutOrRemove(newSessionAttributeName,
                                      newSessionAttributeValue);
                session.bindValue(newSessionAttributeName,
                                  newSessionAttributeValue);
            } else {
                session.doPutOrRemove(newSessionAttributeName,
                                      newSessionAttributeValue);
            }
        }

        // cleanup, remove values from session, that don't exist in data
        // anymore:
        for (String key : currentSessionAttributeNames) {
            if (!newSessionAttributeNames.contains(key)) {
                session.doPutOrRemove(key, null);
                session.unbindValue(key, session.getAttribute(key));
            }
        }
        // trigger event handlers to message activation of the session.
        session.didActivate();

        // return the version of the remote session which was refreshed and is
        // not the active version of current session
        return newSession.getVersion();
    }

    /**
     * Simple session delete from redis.
     */
    @Override
    protected boolean remove(NoSqlSession session) {
        if (session == null) {
            return false;
        }

        String sessionId = session.getId();
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            // del command returns counts of keys successfully deleted.
            return jedis.del(sessionId, sessionId + METADATA_SUFFIX) == 2;
        } catch (Exception e) {
            System.out.println("Exception deleting keys from Redis" + e);
            return false;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    protected void update(NoSqlSession noSqlSession, String s, String s1) throws Exception {
        
    }

    // Metadata management
    public static class Metadata {
        public long version;
        public long lastSaveTime;
    }

    private Metadata getSessionVersionAndLastSaveTimeFromRedis(NoSqlSession session) {
        return getSessionVersionAndLastSaveTimeFromRedis(session.getId());
    }

    private Metadata getSessionVersionAndLastSaveTimeFromRedis(String sessionId) {
        final String key = sessionId + METADATA_SUFFIX;
        Jedis jedis = null;
        try {
            jedis = this.jedisPool.getResource();
            final String value = jedis.get(key);
            if (value == null) {
                return null;
            }
            String[] values = value.split(",");
            Metadata metadata = new Metadata();
            metadata.version = Long.valueOf(values[0]);
            metadata.lastSaveTime = Long.valueOf(values[1]);
            return metadata;
        } catch (Exception e) {
            System.out.println("Exception retrieving the metadata from jedis" + e);
            return null;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }

    }

    private void saveMetadata(NoSqlSession session, Metadata metadata) {
        final String key = session.getId() + METADATA_SUFFIX;
        Jedis jedis = null;
        try {
            jedis = this.jedisPool.getResource();
            jedis.set(key, metadata.version + "," + metadata.lastSaveTime);
        } catch (Exception e) {
            System.out.println("Exception saving metadata to jedis" + e);
            return;
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }

    }

    // serialize/deserialize
    private byte[] serialize(Object obj) {
        try {
            return gson.toJson(obj).getBytes();
        } catch (Exception e) {
            System.out.println("this exception should not occur" + e);
        }
        return null;
    }

    private Map<String, Object> deserialize(byte[] value) {
        try {
            return gson.fromJson(new String(value), HashMap.class);
        } catch (Exception e) {
            System.out.println("Gson couldn't deserialize the object from redis" + e);
        }
        return null;
    }
}
