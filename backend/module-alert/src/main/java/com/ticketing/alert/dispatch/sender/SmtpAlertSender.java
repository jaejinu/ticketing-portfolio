package com.ticketing.alert.dispatch.sender;

import com.ticketing.alert.dispatch.AlertChannelProperties;
import com.ticketing.alert.dispatch.AlertChannelSender;
import com.ticketing.alert.dispatch.DispatchContext;
import com.ticketing.alert.dispatch.DispatchResult;
import com.ticketing.alert.domain.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP 채널 sender — 로컬 MailHog (port 1025) 또는 운영 SMTP.
 *
 * <p>
 *   {@code spring.mail.host} 는 application-local.yml 의 mailhog 기본값 사용.
 *   본 PR 단계엔 plain text 메일. HTML 템플릿은 Phase 8 (운영 콘솔과 함께).
 * </p>
 */
@Component
public class SmtpAlertSender implements AlertChannelSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpAlertSender.class);

    private final JavaMailSender mailSender;
    private final AlertChannelProperties props;

    public SmtpAlertSender(JavaMailSender mailSender, AlertChannelProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @Override
    public String channel() {
        return AlertChannel.SMTP;
    }

    @Override
    public DispatchResult send(DispatchContext ctx) {
        if (ctx.userEmail() == null || ctx.userEmail().isBlank()) {
            return DispatchResult.skipped("user email 누락");
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(props.getSmtp().getFrom());
        msg.setTo(ctx.userEmail());
        msg.setSubject("[티켓팅] 가격 하락 알림");
        msg.setText(String.format(
                "관심 구역의 가격이 %,d원으로 떨어졌습니다.\n목표: %,d원 이하\n\nalertId: %s",
                ctx.observedPrice(), ctx.thresholdPrice(), ctx.alertId()));
        try {
            mailSender.send(msg);
            return DispatchResult.sent();
        } catch (Exception ex) {
            log.warn("SMTP 발송 실패 alertId={}: {}", ctx.alertId(), ex.toString());
            return DispatchResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
