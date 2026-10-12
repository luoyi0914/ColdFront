package io.github.wxmyyds.coldfront.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.github.wxmyyds.coldfront.R
import io.github.wxmyyds.coldfront.domain.CoolerDeviceType

/**
 * 散热器产品图:有图的型号显示官方产品照,其余回退雪花图标。
 * 可指定暗色背景下的图标颜色(如首页仪表盘深色卡)。
 *
 * ⚠️ 本 fork 只服务 REDMI K90 Ultra/Max 的**内置风扇**:`FanManager` 恒定产出
 * `CoolerDeviceType.BUILT_IN_FAN`(见 fan/FanManager.kt:245,306),而 7 张 `img_cooler_*`
 * 产品照只对应 JACKET_* 外置散热器型号——在本机上**永不可达**。那 7 张图已随本次瘦身
 * 删除(641 KB),此处保留雪花图标作为唯一渲染路径。
 *
 * 若将来重新支持外置散热器,需要同时恢复 drawable-nodpi/img_cooler_*.png 与下面的型号映射。
 *
 * 无障碍:刻意不暴露 contentDescription 参数——英雄卡旁边已有文字报出设备名/型号,
 * 图标属装饰性重复信息,按规范应置 null 以免 TalkBack 重报。
 */
@Composable
fun CoolerArt(
    type: CoolerDeviceType?,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        painterResource(R.drawable.materialsymbols_ic_ac_unit_rounded_filled),
        contentDescription = null,
        tint = iconTint,
        modifier = modifier,
    )
}
