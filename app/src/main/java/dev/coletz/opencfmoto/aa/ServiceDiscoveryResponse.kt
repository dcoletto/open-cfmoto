// Adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// Video-only head-unit profile for OpenCfMoto: advertises H.264 video sized for the selected
// BikeModel with margins so the phone renders the UI into a centered panel-sized viewport
// (extracted by SurfaceCropper), a driving-status sensor, a touchscreen input service, and a
// PCM microphone (required for AA bring-up). Audio sink, navigation-status, media-playback and
// bluetooth services from HUR are intentionally dropped (video-only v1 — see docs 03 M5).
package dev.coletz.opencfmoto.aa

import com.google.protobuf.Message
import dev.coletz.opencfmoto.BikeModel
import dev.coletz.opencfmoto.aa.proto.Common
import dev.coletz.opencfmoto.aa.proto.Control
import dev.coletz.opencfmoto.aa.proto.Media
import dev.coletz.opencfmoto.aa.proto.Sensors

class ServiceDiscoveryResponse(bike: BikeModel, densityDpi: Int = bike.densityDpi)
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto(bike, densityDpi)) {

    companion object {
        // AA only streams the fixed codec resolutions below. The BikeModel picks the smallest
        // one containing its panel and declares the leftover as margins: the phone lays out the
        // UI in a centered panel-sized viewport with black bars in the margins, so nothing is
        // stretched, and SurfaceCropper extracts the viewport for the bike encoder.
        private fun codecResolutionFor(bike: BikeModel) = when (bike.aaWidth to bike.aaHeight) {
            800 to 480 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
            1280 to 720 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            1920 to 1080 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
            2560 to 1440 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440
            3840 to 2160 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160
            720 to 1280 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
            1080 to 1920 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
            1440 to 2560 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1440x2560
            2160 to 3840 -> Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2160x3840
            else -> error("${bike.name}: ${bike.aaWidth}x${bike.aaHeight} is not an AA codec resolution")
        }

        private fun makeProto(bike: BikeModel, densityDpi: Int): Message {
            val services = mutableListOf<Control.Service>()

            // --- Sensor service (driving status + night) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // --- Video service (H.264 baseline, 30 fps, geometry from the selected BikeModel) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.availableWhileInCall = true
                    sink.addVideoConfigs(
                        Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                            codecResolution = codecResolutionFor(bike)
                            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            setDensity(densityDpi)
                            setMarginWidth(bike.marginWidth)
                            setMarginHeight(bike.marginHeight)
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // --- Input service (touchscreen; declared for compatibility, driven by voice in v1) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(bike.aaWidth)
                        setHeight(bike.aaHeight)
                    }.build()
                }.build()
            }.build())

            // --- Audio2 sink (system sounds). Android Auto rejects a head unit that advertises
            //     no audio sink and drops the connection right after service discovery, so we
            //     always advertise this even though v1 discards the PCM (see AapMessageHandlerType). ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    sink.audioType = Media.AudioStreamType.SYSTEM
                    sink.addAudioConfigs(
                        Media.AudioConfiguration.newBuilder().apply {
                            sampleRate = 16000
                            numberOfBits = 16
                            numberOfChannels = 1
                        }.build()
                    )
                }.build()
            }.build())

            // --- Microphone service (required for AA connection / Assistant) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also { src ->
                    src.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    src.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build())

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = "OpenCfMoto"
                model = "MotoPlay"
                year = "2024"
                vehicleId = "opencfmoto"
                headUnitModel = "CFDL16-6GUV"
                headUnitMake = "CFMoto"
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName("OpenCfMoto")
                setHeadunitInfo(Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake("CFMoto")
                    setHeadUnitModel("CFDL16-6GUV")
                    setMake("OpenCfMoto")
                    setModel("MotoPlay")
                    setYear("2024")
                    setVehicleId("opencfmoto")
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())
                addAllServices(services)
            }.build()
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor =
            Control.Service.SensorSourceService.Sensor.newBuilder().setType(type).build()
    }
}
