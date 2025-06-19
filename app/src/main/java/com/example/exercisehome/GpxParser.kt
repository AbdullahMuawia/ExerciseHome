package com.example.exercisehome

import android.util.Xml
import org.osmdroid.util.GeoPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object GpxParser {
    fun parse(stream: InputStream): List<GeoPoint> {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val points = mutableListOf<GeoPoint>()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "trkpt") {
                try {
                    val lat = parser.getAttributeValue(null, "lat").toDouble()
                    val lon = parser.getAttributeValue(null, "lon").toDouble()
                    points.add(GeoPoint(lat, lon))
                } catch (e: Exception) {
                    // Ignore points that can't be parsed
                }
            }
            eventType = parser.next()
        }
        return points
    }
}
