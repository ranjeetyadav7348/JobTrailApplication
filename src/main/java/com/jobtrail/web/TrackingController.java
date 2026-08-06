package com.jobtrail.web;

import com.jobtrail.service.OutreachService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves the open-tracking pixel embedded in outgoing mail. */
@RestController
@RequiredArgsConstructor
public class TrackingController {

    /** A 43-byte fully transparent 1x1 GIF. */
    private static final byte[] PIXEL = {
            (byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38, (byte) 0x39, (byte) 0x61,
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x80, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0x21, (byte) 0xF9, (byte) 0x04, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x2C, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x02, (byte) 0x44, (byte) 0x01, (byte) 0x00,
            (byte) 0x3B
    };

    private final OutreachService outreachService;

    @GetMapping("/t/{token}.gif")
    public ResponseEntity<byte[]> pixel(@PathVariable String token) {
        try {
            outreachService.recordOpen(token);
        } catch (Exception ignored) {
            // A tracking failure must never turn into a broken image in someone's inbox.
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header("Pragma", "no-cache")
                .body(PIXEL);
    }
}
