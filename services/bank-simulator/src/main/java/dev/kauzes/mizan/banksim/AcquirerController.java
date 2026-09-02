package dev.kauzes.mizan.banksim;

import dev.kauzes.mizan.banksim.AcquirerRequests.AuthorizationResponse;
import dev.kauzes.mizan.banksim.AcquirerRequests.AuthorizeRequest;
import dev.kauzes.mizan.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An acquirer, as far as the platform is concerned.
 *
 * <p>Not under {@code /api/}, and not routed through the gateway, because this is not part of
 * the platform's API: it stands in for somebody else's system. A merchant cannot reach it and
 * should not know it exists.
 */
@RestController
@RequestMapping(path = "/acquirer", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Acquirer", description = "A bank, for the purposes of not needing one")
public class AcquirerController {

    private final Acquirer acquirer;

    public AcquirerController(Acquirer acquirer) {
        this.acquirer = acquirer;
    }

    @PostMapping("/authorizations")
    @Operation(
            summary = "Authorize an amount",
            description =
                    """
                    Reserves the money, or refuses. What this acquirer does is decided by the \
                    last four digits of the card, so a caller can provoke any outcome without \
                    knowing it is talking to a simulator:

                    - `0000`, or anything not listed: approved
                    - `0002`: declined, insufficient_funds
                    - `0005`: declined, do_not_honour
                    - `0007`: declined, stolen_card
                    - `0069`: approved, but the answer takes longer than the caller will wait
                    - `0068`: declined, but likewise. A caller who gave up cannot tell this                     apart from `0069`, which is why resolving a timeout means asking rather                     than assuming

                    Sending the same requestId again returns the first decision rather than \
                    authorizing a second time, which is what a real acquirer does and what \
                    makes a lost answer safe to ask about again.""")
    @ApiResponse(responseCode = "200", description = "What the acquirer decided")
    @ApiResponse(responseCode = "400", ref = "#/components/responses/VALIDATION_FAILED")
    public AuthorizationResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        return acquirer.authorize(request);
    }

    @PostMapping("/authorizations/{acquirerReference}/capture")
    @Operation(summary = "Take an authorized amount")
    @ApiResponse(responseCode = "200", description = "The authorization, now captured")
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NOT_FOUND")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "It was refused, or has already been taken or released")
    public AuthorizationResponse capture(@PathVariable String acquirerReference) {
        return acquirer.capture(acquirerReference);
    }

    @PostMapping("/authorizations/{acquirerReference}/void")
    @Operation(summary = "Release an authorized amount without taking it")
    @ApiResponse(responseCode = "200", description = "The authorization, now voided")
    @ApiResponse(responseCode = "404", ref = "#/components/responses/NOT_FOUND")
    @ApiResponse(
            responseCode = "422",
            ref = "#/components/responses/UNPROCESSABLE",
            description = "It was refused, or has already been taken or released")
    public AuthorizationResponse voidAuthorization(@PathVariable String acquirerReference) {
        return acquirer.voidAuthorization(acquirerReference);
    }

    @GetMapping("/authorizations")
    @Operation(
            summary = "Ask what happened to a request",
            description =
                    """
                    Looked up by the requestId the caller chose, not by the acquirer's own \
                    reference, because a caller whose answer was lost never learned the \
                    reference.

                    This is what turns a timeout from a guess into a question.""")
    @ApiResponse(responseCode = "200", description = "What was decided")
    @ApiResponse(
            responseCode = "404",
            ref = "#/components/responses/NOT_FOUND",
            description = "This acquirer has no record of that request, so nothing happened")
    public AuthorizationResponse lookUp(
            @org.springframework.web.bind.annotation.RequestParam String requestId) {

        return acquirer
                .lookUp(requestId)
                .orElseThrow(() -> new NotFoundException("No record of that request."));
    }
}
