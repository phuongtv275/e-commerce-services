package com.example.identityservice.security.principal;

import com.example.identityservice.models.entities.User;
import com.example.identityservice.models.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findWithRolesByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + username));
        return MyUserDetails.builder()
                .user(user)
                .authorities(user.getRoles().stream().map(
                        r -> new SimpleGrantedAuthority(r.getRoleName().name())
                ).toList())
                .build();
    }
}
