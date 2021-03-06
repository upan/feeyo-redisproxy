package com.feeyo.redis.net.front.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.redis.engine.codec.RedisPipelineResponseDecoder;
import com.feeyo.redis.engine.codec.RedisResponseV3;
import com.feeyo.redis.engine.manage.stat.StatUtil;
import com.feeyo.redis.net.backend.RedisBackendConnection;
import com.feeyo.redis.net.backend.callback.DirectTransTofrontCallBack;
import com.feeyo.redis.net.front.RedisFrontConnection;
import com.feeyo.redis.net.front.route.RouteResult;
import com.feeyo.redis.net.front.route.RouteResultNode;
import com.feeyo.redis.nio.util.TimeUtil;
import com.feeyo.util.ProtoUtils;

/**
 * 
 * 用于解决 集群情况下删除多个 key，跨节点的问题
 * 
 * (error) CROSSSLOT Keys in request don't hash to the same slot
 * 
 * @author zhuam
 *
 */
public class DelMultiKeyCommandHandler extends AbstractPipelineCommandHandler {
	
	private static Logger LOGGER = LoggerFactory.getLogger( DelMultiKeyCommandHandler.class );
	
	public final static String MULTI_DEL_CMD = "MULTI_DEL";
	public final static byte[] MULTI_DEL_KEY = MULTI_DEL_CMD.getBytes();

	public DelMultiKeyCommandHandler(RedisFrontConnection frontCon) {
		super(frontCon);
	}
	
	@Override
	protected void commonHandle(RouteResult rrs) throws IOException {
		
		super.commonHandle(rrs);
		
		// 写出
		for (RouteResultNode rrn : rrs.getRouteResultNodes()) {
			ByteBuffer buffer = getRequestBufferByRRN(rrn);
			RedisBackendConnection backendConn = writeToBackend( rrn.getPhysicalNode(), buffer, new DelCallBack()); 
			if ( backendConn != null )
				this.addBackendConnection(backendConn);
		}
		
		// 埋点
		frontCon.getSession().setRequestTimeMills(TimeUtil.currentTimeMillis());
		frontCon.getSession().setRequestCmd( MULTI_DEL_CMD );
		frontCon.getSession().setRequestKey( MULTI_DEL_KEY );
		frontCon.getSession().setRequestSize( rrs.getRequestSize() );
		
	}
	
	/**
	  127.0.0.1:6379> del aa bb cc dd
	  (integer) 2
	 */
	private class DelCallBack extends DirectTransTofrontCallBack {

		private RedisPipelineResponseDecoder decoder = new RedisPipelineResponseDecoder();
		
		private byte[] encode(int count) {
			// 编码
			byte[] countBytes = ProtoUtils.convertIntToByteArray( count );
            ByteBuffer buffer = ByteBuffer.allocate( 1 + 2 + countBytes.length); //   cmd length + \r\n length + count bytes length
            buffer.put((byte)':');
            buffer.put( countBytes );
            buffer.put("\r\n".getBytes());
            
            buffer.flip();
            byte[] data = new byte[ buffer.remaining() ];
            buffer.get( data );
            return data;
		}
		
		private int readInt(byte[] buf) {
			int idx = 0;
			final boolean isNeg = buf[idx] == '-';
			if (isNeg) {
				++idx;
			}

			int value = 0;
			while (true) {
				final int b = buf[idx++];
				if (b == '\r' && buf[idx++] == '\n') {
					break;
				} else {
					value = value * 10 + b - '0';
				}
			}
			return value;
		}

		@Override
		public void handleResponse(RedisBackendConnection backendCon, byte[] byteBuff) throws IOException {

			int count = decoder.parseResponseCount(byteBuff);
			if (count <= 0) {
				return;
			}

			String address = backendCon.getPhysicalNode().getName();
			byte[] data = decoder.getBuffer();
			decoder.clearBuffer();
			
			ResponseStatusCode state = recvResponse(address, count, data);
			if ( state == ResponseStatusCode.ALL_NODE_COMPLETED ) {
				
				List<RedisResponseV3> resps = margeResponses();
				if (resps != null) {
					try {
						String password = frontCon.getPassword();
						String cmd = frontCon.getSession().getRequestCmd();
						byte[] key = frontCon.getSession().getRequestKey();
						int requestSize = frontCon.getSession().getRequestSize();
						long requestTimeMills = frontCon.getSession().getRequestTimeMills();
						long responseTimeMills = TimeUtil.currentTimeMillis();
						
						int okCount = 0;
						for (RedisResponseV3 resp : resps) {
							if ( resp.is( (byte)':') ) {
								byte[] _buf1 = (byte[])resp.data();
								byte[] buf2 = new byte[ _buf1.length - 1 ];  // type+data+\r\n  ->  data+\r\n
								System.arraycopy(_buf1, 1, buf2, 0, buf2.length);
								int c = readInt( buf2 );
								okCount += c;
							}
						}
						
						RedisResponseV3 newResp = new RedisResponseV3((byte)':', encode( okCount ));
	                    int responseSize = this.writeToFront(frontCon, newResp, 0);

						// 释放
						removeAndReleaseBackendConnection(backendCon);

						// 数据收集
						StatUtil.collect(password, cmd, key, requestSize, responseSize,
								(int) (responseTimeMills - requestTimeMills), false);

					} catch (IOException e2) {
						if (frontCon != null) {
							frontCon.close("write err");
						}

						// 由 reactor close
						LOGGER.error("backend write to front err:", e2);
						throw e2;
						
					} finally {
						// 释放锁
						frontCon.releaseLock();
					}
				}

			} else if ( state == ResponseStatusCode.THE_NODE_COMPLETED  ) {
				
				// 如果此后端节点数据已经返回完毕，则释放链接
				removeAndReleaseBackendConnection(backendCon);
				
			} else if ( state == ResponseStatusCode.ERROR ) {
				// 添加回复到虚拟内存中出错。
				responseAppendError();
			}
		}
	}

}