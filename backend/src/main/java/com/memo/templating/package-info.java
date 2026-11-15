/**
 * Document templates (ADR-0048): .docx templates with typed tags, checked on upload and filled on the server. Used by the
 * memo module (memo types) and, later, by meeting minutes.
 */
@ApplicationModule(displayName = "Document templates")
@NullMarked
package com.memo.templating;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
