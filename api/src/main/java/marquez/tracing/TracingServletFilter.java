/*
 * Copyright 2018-2023 contributors to the Marquez project
 * SPDX-License-Identifier: Apache-2.0
 */

package marquez.tracing;

import io.sentry.ITransaction;
import io.sentry.Sentry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

public class TracingServletFilter implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    ITransaction transaction = transaction(request, response);
    Sentry.configureScope(
        scope -> {
          scope.setTransaction(transaction);
        });
    try {
      chain.doFilter(request, response);
    } finally {
      transaction.finish();
    }
  }

  private ITransaction transaction(ServletRequest request, ServletResponse response) {
    String transactionName = request.getProtocol();
    String taskName = request.getProtocol();
    String description = "";
    if (request instanceof HttpServletRequest) {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      transactionName += " " + httpRequest.getMethod();
      taskName = httpRequest.getMethod() + " " + httpRequest.getPathInfo();
      description =
          (httpRequest.getQueryString() == null ? "" : httpRequest.getQueryString() + "\n")
              + "Input size: "
              + request.getContentLengthLong();
    }
    return Sentry.startTransaction(transactionName, taskName, description);
  }
}
