/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.api.exceptions;

import static com.google.common.base.Preconditions.checkNotNull;

import jakarta.ws.rs.NotFoundException;
import marquez.common.models.TagName;

public final class TagNotFoundException extends NotFoundException {
  private static final long serialVersionUID = 1L;

  public TagNotFoundException(final TagName name) {
    super(String.format("Tag '%s' not found.", checkNotNull(name.getValue())));
  }
}
