package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.SendChatMessageRequest;
import org.coryd.trip.entity.ChatMessage;
import org.coryd.trip.service.ChatService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Trip Group Chat - history loads over REST, new messages arrive live over the same STOMP
 * broker used for live location (/topic/routes/{routePostId}/chat), authorized the same way. */
@RestController
@RequestMapping("/routes/{routePostId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/messages")
    public ApiResponse<List<ChatMessage>> messages(@AuthenticationPrincipal CoRydUserDetails principal,
                                                     @PathVariable String routePostId) {
        return ApiResponse.ok(chatService.getMessages(routePostId, principal.getUserId()));
    }

    @PostMapping("/messages")
    public ApiResponse<ChatMessage> send(@AuthenticationPrincipal CoRydUserDetails principal,
                                          @PathVariable String routePostId,
                                          @Valid @RequestBody SendChatMessageRequest request) {
        return ApiResponse.ok(chatService.sendMessage(routePostId, principal.getUserId(), request));
    }
}
