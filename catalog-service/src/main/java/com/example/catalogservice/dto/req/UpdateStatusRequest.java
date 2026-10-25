package com.example.catalogservice.dto.req;

import jakarta.validation.constraints.NotNull;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStatusRequest<T> {

    @NotNull(message = "Status cannot be null")
    private T status;
}
