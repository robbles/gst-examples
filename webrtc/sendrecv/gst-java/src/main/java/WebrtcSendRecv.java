import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.ws.WebSocket;
import org.asynchttpclient.ws.WebSocketListener;
import org.asynchttpclient.ws.WebSocketUpgradeHandler;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.Element.PAD_ADDED;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.PadProbeType;
import org.freedesktop.gstreamer.elements.DecodeBin;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import org.freedesktop.gstreamer.webrtc.WebRTCBin.CREATE_OFFER;
import org.freedesktop.gstreamer.webrtc.WebRTCBin.ON_ICE_CANDIDATE;
import org.freedesktop.gstreamer.webrtc.WebRTCBin.ON_NEGOTIATION_NEEDED;
import org.freedesktop.gstreamer.webrtc.WebRTCSessionDescription;
import org.freedesktop.gstreamer.webrtc.WebRTCSDPType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Demo gstreamer app for negotiating and streaming a sendrecv webrtc stream
 * with a browser JS app.
 *
 * @author stevevangasse
 */
public class WebrtcSendRecv {

    private static final Logger logger = LoggerFactory.getLogger(WebrtcSendRecv.class);
    private static final String REMOTE_SERVER_URL = "wss://webrtc.nirbheek.in:8443";

    private static final String VIDEO_BIN_DESCRIPTION = String.join(
        " ! ",
        "videotestsrc",
        "video/x-raw,width=320,height=480",
        "clockoverlay",
        "compositor name=mixer sink_0::alpha=1 sink_1::alpha=1 sink_1::xpos=20 sink_1::ypos=20",
        "videoconvert",
        "tee name=rtmp_tee",
        "queue",
        "vp8enc deadline=1",
        "rtpvp8pay",
        "queue",
        "capsfilter caps=application/x-rtp,media=video,encoding-name=VP8,payload=97"
    );

    private static final String RTMP_BIN_DESCRIPTION = String.join(
        " ! ",
        "queue",
        "x264enc",
        "flvmux name=muxer",
        "rtmpsink location=\"%s live=1\""
    );

    private static final String AUDIO_BIN_DESCRIPTION = "audiotestsrc ! audioconvert ! audioresample ! queue ! opusenc ! rtpopuspay ! queue ! capsfilter caps=application/x-rtp,media=audio,encoding-name=OPUS,payload=96";

    private final String serverUrl;
    private final String peerId;
    private final ObjectMapper mapper = new ObjectMapper();
    private WebSocket websocket;
    private WebRTCBin webRTCBin;
    private Bin videoTestSource;
    private Bin audioTestSource;
    private Element rtmpSink;
    private Pipeline pipe;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            logger.error("Please pass at least the peer-id from the signalling server e.g java -jar build/libs/gst-java.jar --peer-id=1234 --server=wss://webrtc.nirbheek.in:8443");
            return;
        }
        String serverUrl = REMOTE_SERVER_URL;
        String peerId = null;
        String rtmpURI = "";
        for (int i=0; i<args.length; i++) {
            if (args[i].startsWith("--server=")) {
                serverUrl = args[i].substring("--server=".length());
            } else if (args[i].startsWith("--peer-id=")) {
                peerId = args[i].substring("--peer-id=".length());
            } else if (args[i].startsWith("--rtmp-uri=")) {
                rtmpURI = args[i].substring("--rtmp-uri=".length());
            }
        }
        logger.info("Using peer id {}, on server: {}", peerId, serverUrl);
        if(!rtmpURI.isEmpty()) {
            logger.info("Sending to RTMP URI {}", rtmpURI);
        } else {
            logger.warn("Not sending to RTMP, no URI provided");
        }
        WebrtcSendRecv webrtcSendRecv = new WebrtcSendRecv(peerId, serverUrl, rtmpURI);
        webrtcSendRecv.startCall();
    }

    private WebrtcSendRecv(String peerId, String serverUrl, String rtmpURI) {
        this.peerId = peerId;
        this.serverUrl = serverUrl;
        Gst.init(new Version(1, 14));

        logger.info("Using gstreamer version {}", Gst.getVersionString());
        webRTCBin = new WebRTCBin("sendrecv");

        videoTestSource = Gst.parseBinFromDescription(VIDEO_BIN_DESCRIPTION, true);
        audioTestSource = Gst.parseBinFromDescription(AUDIO_BIN_DESCRIPTION, true);
        if(!rtmpURI.isEmpty()) {
            rtmpSink = Gst.parseBinFromDescription(String.format(RTMP_BIN_DESCRIPTION, rtmpURI), true);
            logger.info(String.format(RTMP_BIN_DESCRIPTION, rtmpURI));
        } else {
            rtmpSink = ElementFactory.make("fakesink", "dummy-rtmp-sink");
        }

        pipe = new Pipeline();
        pipe.addMany(webRTCBin, videoTestSource, audioTestSource);
        videoTestSource.link(webRTCBin);
        audioTestSource.link(webRTCBin);
        setupPipeLogging(pipe);

        // When the pipeline goes to PLAYING, the on_negotiation_needed() callback will be called, and we will ask webrtcbin to create an offer which will match the pipeline above.
        webRTCBin.connect(onNegotiationNeeded);
        webRTCBin.connect(onIceCandidate);
        webRTCBin.connect(onIncomingStream);
    }

    private void startCall() throws Exception {
        DefaultAsyncHttpClientConfig httpClientConfig =
                new DefaultAsyncHttpClientConfig
                        .Builder()
                        .setUseInsecureTrustManager(true)
                        .build();

        websocket = new DefaultAsyncHttpClient(httpClientConfig)
                .prepareGet(serverUrl)
                .execute(
                        new WebSocketUpgradeHandler
                                .Builder()
                                .addWebSocketListener(webSocketListener)
                                .build())
                .get();

        Gst.main();
    }

    private WebSocketListener webSocketListener = new WebSocketListener() {

        @Override
        public void onOpen(WebSocket websocket) {
            logger.info("websocket onOpen");
            websocket.sendTextFrame("HELLO 564322");
        }

        @Override
        public void onClose(WebSocket websocket, int code, String reason) {
            logger.info("websocket onClose: " + code + " : " + reason);
            Gst.quit();
            System.exit(0);
        }

        @Override
        public void onTextFrame(String payload, boolean finalFragment, int rsv) {
            if (payload.equals("HELLO")) {
                websocket.sendTextFrame("SESSION " + peerId);
            } else if (payload.equals("SESSION_OK")) {
                pipe.play();
            } else if (payload.startsWith("ERROR")) {
                logger.error(payload);
                Gst.quit();
            } else {
                handleSdp(payload);
            }
        }

        @Override
        public void onError(Throwable t) {
            logger.error("onError", t);
        }
    };

    private void handleSdp(String payload) {
        try {
            JsonNode answer = mapper.readTree(payload);
            if (answer.has("sdp")) {
                String sdpStr = answer.get("sdp").get("sdp").textValue();
                logger.info("answer SDP:\n{}", sdpStr);
                SDPMessage sdpMessage = new SDPMessage();
                sdpMessage.parseBuffer(sdpStr);
                WebRTCSessionDescription description = new WebRTCSessionDescription(WebRTCSDPType.ANSWER, sdpMessage);
                webRTCBin.setRemoteDescription(description);
            }
            else if (answer.has("ice")) {
                String candidate = answer.get("ice").get("candidate").textValue();
                int sdpMLineIndex = answer.get("ice").get("sdpMLineIndex").intValue();
                logger.info("Adding ICE candidate: {}", candidate);
                webRTCBin.addIceCandidate(sdpMLineIndex, candidate);
            }
        } catch (IOException e) {
            logger.error("Problem reading payload", e);
        }
    }

    private CREATE_OFFER onOfferCreated = offer -> {
        webRTCBin.setLocalDescription(offer);
        try {
            JsonNode rootNode = mapper.createObjectNode();
            JsonNode sdpNode = mapper.createObjectNode();
            ((ObjectNode) sdpNode).put("type", "offer");
            ((ObjectNode) sdpNode).put("sdp", offer.getSDPMessage().toString());
            ((ObjectNode) rootNode).set("sdp", sdpNode);
            String json = mapper.writeValueAsString(rootNode);
            logger.info("Sending offer:\n{}", json);
            websocket.sendTextFrame(json);
        } catch (JsonProcessingException e) {
            logger.error("Couldn't write JSON", e);
        }
    };

    private ON_NEGOTIATION_NEEDED onNegotiationNeeded = elem -> {
        logger.info("onNegotiationNeeded: " + elem.getName());

        // When webrtcbin has created the offer, it will hit our callback and we send SDP offer over the websocket to signalling server
        webRTCBin.createOffer(onOfferCreated);
    };

    private ON_ICE_CANDIDATE onIceCandidate = (sdpMLineIndex, candidate) -> {
        JsonNode rootNode = mapper.createObjectNode();
        JsonNode iceNode = mapper.createObjectNode();
        ((ObjectNode) iceNode).put("candidate", candidate);
        ((ObjectNode) iceNode).put("sdpMLineIndex", sdpMLineIndex);
        ((ObjectNode) rootNode).set("ice", iceNode);

        try {
            String json = mapper.writeValueAsString(rootNode);
            logger.info("ON_ICE_CANDIDATE: " + json);
            websocket.sendTextFrame(json);
        } catch (JsonProcessingException e) {
            logger.error("Couldn't write JSON", e);
        }
    };


    private PAD_ADDED onIncomingDecodebinStream = (element, pad) -> {
        logger.info("onIncomingDecodebinStream from {}", element.getName());
        if (!pad.hasCurrentCaps()) {
            logger.info("Pad has no caps, ignoring: {}", pad.getName());
            return;
        }
        Structure caps = pad.getCurrentCaps().getStructure(0);
        String name = caps.getName();
        if (name.startsWith("video")) {
            logger.info("onIncomingDecodebinStream video");

            // Bin that converts and scales video
            Bin videotransform = Gst.parseBinFromDescription(String.join(
                " ! ",
                "queue",
                "videoconvert",
                "videoscale",
                "video/x-raw,width=160,height=120",
                "queue"
            ), true);

            pipe.addMany(videotransform, rtmpSink);
            videotransform.syncStateWithParent();
            rtmpSink.syncStateWithParent();

            // Connect new pad to videotransform pipeline
            pad.link(videotransform.getStaticPad("sink"));

            // Connect to second sink on mixer
            Element mixer = videoTestSource.getElementByName("mixer");
            Element.linkPads(videotransform, "src", mixer, "sink_1");

            Element rtmpTee = videoTestSource.getElementByName("rtmp_tee");
            Element.linkPads(rtmpTee, "src_1", rtmpSink, "sink");
        }
        if (name.startsWith("audio")) {
            logger.info("onIncomingDecodebinStream audio");
            Element queue = ElementFactory.make("queue", "my-audioqueue");
            Element audioconvert = ElementFactory.make("audioconvert", "my-audioconvert");
            Element audioresample = ElementFactory.make("audioresample", "my-audioresample");
            Element fakeaudiosink = ElementFactory.make("fakesink", "my-autoaudiosink");
            pipe.addMany(queue, audioconvert, audioresample, fakeaudiosink);
            queue.syncStateWithParent();
            audioconvert.syncStateWithParent();
            audioresample.syncStateWithParent();
            fakeaudiosink.syncStateWithParent();
            pad.link(queue.getStaticPad("sink"));
            queue.link(audioconvert);
            audioconvert.link(audioresample);
            audioresample.link(fakeaudiosink);
        }
    };

    private PAD_ADDED onIncomingStream = (element, pad) -> {
        if (pad.getDirection() != PadDirection.SRC) {
            logger.info("Pad is not source, ignoring: {}", pad.getDirection());
            return;
        }
        logger.info("Receiving stream! Element: {} Pad: {}", element.getName(), pad.getName());
        DecodeBin decodebin = new DecodeBin("my-decoder-" + pad.getName());
        decodebin.connect(onIncomingDecodebinStream);
        pipe.add(decodebin);
        decodebin.syncStateWithParent();
        webRTCBin.link(decodebin);
    };

    private void setupPipeLogging(Pipeline pipe) {
        Bus bus = pipe.getBus();
        bus.connect((Bus.EOS) source -> {
            logger.info("Reached end of stream: " + source.toString());
            Gst.quit();
        });

        bus.connect((Bus.ERROR) (source, code, message) -> {
            logger.error("Error from source: '{}', with code: {}, and message '{}'", source, code, message);
        });

        bus.connect((source, old, current, pending) -> {
            if (source instanceof Pipeline) {
                logger.info("Pipe state changed from {} to new {}", old, current);
            }
        });
    }
}

