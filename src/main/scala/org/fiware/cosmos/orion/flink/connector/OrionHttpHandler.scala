/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by @sonsoleslp
 */
package org.fiware.cosmos.orion.flink.connector

import io.netty.buffer.{ByteBufUtil, Unpooled}
import io.netty.channel.{ChannelFutureListener, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.HttpResponseStatus.{CONTINUE, OK}
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http.{DefaultFullHttpResponse, FullHttpRequest, FullHttpResponse, HttpMethod, HttpUtil}
import io.netty.util.{AsciiString, CharsetUtil}
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory
/**
 * HTTP server handler, HTTP http request
 *
 * @param sc       Flink source context for collect received message
 */
class OrionHttpHandler(
  sc: SourceContext[NgsiEvent]
) extends OrionHttpHandlerInterface (sc: SourceContext[NgsiEvent],NgsiEvent.getClass) {

  private lazy val logger = LoggerFactory.getLogger(getClass)

  override def parseMessage(req : FullHttpRequest) : NgsiEvent =  {
    try {
      // Retrieve headers
      val headerEntries = req.headers().entries()
      val SERVICE_HEADER = 4
      val SERVICE_PATH_HEADER = 5
      val service = headerEntries.get(SERVICE_HEADER).getValue()
      val servicePath = headerEntries.get(SERVICE_PATH_HEADER).getValue()

      // Retrieve body content and convert from Byte array to String
      val content = req.content()
      val byteBufUtil = ByteBufUtil.readBytes(content.alloc, content, content.readableBytes)
      val jsonBodyString = byteBufUtil.toString(0,content.capacity(),CharsetUtil.UTF_8)
      content.release()
      // Parse Body from JSON string to object and retrieve entities
      val dataObj = parse(jsonBodyString).extract[HttpBody]
      val parsedEntities = dataObj.data
      val subscriptionId = dataObj.subscriptionId
      val entities = parsedEntities.map(entity => {
        // Retrieve entity id
        val entityId = entity("id").toString
        // Retrieve entity type
        val entityType = entity("type").toString
        // Retrieve attributes
        val attrs = entity.filterKeys(x => x != "id" & x!= "type" )
          //Convert attributes to Attribute objects
          .transform((k,v) => MapToAttributeConverter
          .unapply(v.asInstanceOf[Map[String,Any]]))
        Entity(entityId, entityType, attrs)
      })
      // Generate timestamp
      val creationTime = System.currentTimeMillis
      // Generate NgsiEvent
      val ngsiEvent = NgsiEvent(creationTime, service, servicePath, entities, subscriptionId)
      ngsiEvent
    } catch {
      case e: Exception => null
      case e: Error => null
    }
  }
  override def sendMessage(msg: scala.Serializable) : Unit = {
    val ngsiEvent = msg.asInstanceOf[NgsiEvent]
    logger.info(write(ngsiEvent))
    sc.collect(ngsiEvent)
  }

}
