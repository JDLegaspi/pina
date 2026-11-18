package dev.shulhi.rex

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal object SettingsType {
    val title=PinaType.title3.copy(fontWeight=FontWeight.Bold)
    val row=PinaType.subheadline.copy(fontWeight=FontWeight.Medium)
    val body=PinaType.subheadline
    val detail=PinaType.footnote
    val section=PinaType.footnote.copy(fontWeight=FontWeight.SemiBold)
}

@Composable internal fun SettingsSection(title: String,content: @Composable ColumnScope.()->Unit) {
    InsetGroupedSection(header=title,horizontalInset=0.dp,content=content)
}

@Composable internal fun SettingsDivider() {
    GroupedDivider(GroupedRowDefaults.LeadingTextInset)
}

internal enum class SettingsGlyph(val color: (PinaColors)->Color) {
    APPEARANCE({it.tint}),FONT({it.tint}),ALERTS({it.red}),UPLOAD({it.green}),INFO({it.gray}),
    SERVER({it.green}),COMMAND({it.gray}),NETWORK({it.tint}),KEY({it.orange})
}

@Composable internal fun SettingsRow(title: String,subtitle: String,glyph: SettingsGlyph,onClick: ()->Unit,badge: String?=null) {
    val colors=LocalPinaColors.current
    GroupedRow(title,leading={SettingsIcon(glyph)},subtitle=subtitle,onClick=onClick,trailing={
        badge?.let {Text(it,style=PinaType.subheadline,color=colors.secondaryLabel)}
        GroupedChevron()
    })
}

@Composable internal fun SettingsIcon(glyph: SettingsGlyph) {
    val color=Color.White
    Surface(shape=RoundedCornerShape(7.dp),color=glyph.color(LocalPinaColors.current)) {
        Box(Modifier.size(GroupedRowDefaults.LeadingSize),contentAlignment=Alignment.Center) {
            if(glyph==SettingsGlyph.FONT) Text("Aa",style=PinaType.footnote.copy(fontWeight=FontWeight.SemiBold),color=color)
            else Canvas(Modifier.size(18.dp)) {
                val u=size.minDimension/24f
                val stroke=Stroke(1.7f*u,cap=StrokeCap.Round)
                fun line(x1: Float,y1: Float,x2: Float,y2: Float)=drawLine(color,Offset(x1*u,y1*u),Offset(x2*u,y2*u),1.7f*u,StrokeCap.Round)
                when(glyph) {
                    SettingsGlyph.APPEARANCE->{
                        drawCircle(color,8f*u,style=stroke)
                        drawArc(color,-90f,180f,true,Offset(4f*u,4f*u),Size(16f*u,16f*u))
                    }
                    SettingsGlyph.ALERTS->{
                        val path=androidx.compose.ui.graphics.Path().apply {
                            moveTo(5f*u,17f*u);lineTo(7f*u,14f*u);lineTo(7f*u,10f*u)
                            cubicTo(7f*u,3f*u,17f*u,3f*u,17f*u,10f*u)
                            lineTo(17f*u,14f*u);lineTo(19f*u,17f*u);close()
                        }
                        drawPath(path,color,style=stroke);line(10f,21f,14f,21f);line(12f,3f,12f,4f)
                    }
                    SettingsGlyph.UPLOAD->{line(12f,16f,12f,4f);line(7f,9f,12f,4f);line(12f,4f,17f,9f);line(4f,16f,4f,21f);line(4f,21f,20f,21f);line(20f,21f,20f,16f)}
                    SettingsGlyph.INFO->{drawCircle(color,9f*u,style=stroke);drawCircle(color,1f*u,Offset(12f*u,7f*u));line(12f,11f,12f,17f)}
                    SettingsGlyph.SERVER->{
                        for(y in listOf(4f,14f)) {
                            drawRoundRect(color,Offset(3f*u,y*u),Size(18f*u,6f*u),CornerRadius(1.5f*u),style=stroke)
                            drawCircle(color,.8f*u,Offset(7f*u,(y+3)*u))
                        }
                    }
                    SettingsGlyph.NETWORK->{
                        line(12f,8f,12f,12f);line(5f,12f,19f,12f)
                        line(5f,12f,5f,16f);line(19f,12f,19f,16f)
                        for((x,y) in listOf(12f to 5f,5f to 19f,19f to 19f)) {
                            drawCircle(color,3f*u,Offset(x*u,y*u),style=stroke)
                        }
                    }
                    SettingsGlyph.KEY->{drawCircle(color,4f*u,Offset(7f*u,8f*u),style=stroke);line(10f,11f,20f,21f);line(15f,16f,18f,13f);line(18f,19f,21f,16f)}
                    SettingsGlyph.FONT->Unit
                    SettingsGlyph.COMMAND->{line(9f,6f,3f,12f);line(3f,12f,9f,18f);line(15f,6f,21f,12f);line(21f,12f,15f,18f)}
                }
            }
        }
    }
}
