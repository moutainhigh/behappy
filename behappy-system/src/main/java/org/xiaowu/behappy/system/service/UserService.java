package org.xiaowu.behappy.system.service;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.xiaowu.behappy.common.core.exception.BeHappyException;
import org.xiaowu.behappy.system.dto.UserLoginDto;
import org.xiaowu.behappy.system.dto.UserPasswordDto;
import org.xiaowu.behappy.system.entity.UserEntity;
import org.xiaowu.behappy.system.mapper.RoleMapper;
import org.xiaowu.behappy.system.mapper.RoleMenuMapper;
import org.xiaowu.behappy.system.mapper.UserMapper;
import org.xiaowu.behappy.system.vo.MenuVo;
import org.xiaowu.behappy.system.vo.UserVo;

import java.util.ArrayList;
import java.util.List;

import static org.xiaowu.behappy.system.enums.BizCode.ORIGINAL_PASSWORD_NOT_CORRECT;
import static org.xiaowu.behappy.system.enums.BizCode.USER_NOT_EXISTS;

/**
 * @author xiaowu
 */
@Slf4j
@Service
@AllArgsConstructor
public class UserService extends ServiceImpl<UserMapper, UserEntity> implements IService<UserEntity> {

    private final UserMapper userMapper;

    private final RoleMapper roleMapper;

    private final RoleMenuMapper roleMenuMapper;

    private final MenuService menuService;

    private final SaTokenConfig saTokenConfig;

    private final PasswordEncoder passwordEncoder;

    public void updatePassword(UserPasswordDto userPasswordDTO) {
        UserEntity userInfo = getUserInfo(userPasswordDTO.getUsername());
        if (passwordEncoder.matches(userPasswordDTO.getPassword(), userInfo.getPassword())) {
            userPasswordDTO.setNewPassword(passwordEncoder.encode(userPasswordDTO.getNewPassword()));
            userMapper.updatePassword(userPasswordDTO);
            return;
        }
        throw new BeHappyException(ORIGINAL_PASSWORD_NOT_CORRECT.getCode(), ORIGINAL_PASSWORD_NOT_CORRECT.getMsg());
    }

    public Page findPage(Page<UserEntity> page, String username, String email, String address) {
        Page voPage = userMapper.findPage(page, username, email, address);
        List<UserVo> userVos = BeanUtil.copyToList(voPage.getRecords(), UserVo.class, CopyOptions.create());
        voPage.setRecords(userVos);
        return voPage;
    }

    private UserEntity getUserInfo(String username) {
        QueryWrapper<UserEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", username);
        UserEntity one = getOne(queryWrapper);
        return one;
    }

    /**
     * ?????????????????????????????????
     * @param roleFlag
     * @return
     */
    private List<MenuVo> getRoleMenus(String roleFlag) {
        Long roleId = roleMapper.selectByFlag(roleFlag);
        // ???????????????????????????id??????
        List<Long> menuIds = roleMenuMapper.selectByRoleId(roleId);

        // ???????????????????????????(??????)
        List<MenuVo> menus = menuService.findMenus("");
        // new?????????????????????????????????list
        List<MenuVo> roleMenus = new ArrayList<>();
        // ?????????????????????????????????
        for (MenuVo menu : menus) {
            if (menuIds.contains(menu.getId())) {
                roleMenus.add(menu);
            }
            List<MenuVo> children = menu.getChildren();
            // removeIf()  ?????? children ???????????? menuIds???????????? ??????
            children.removeIf(child -> !menuIds.contains(child.getId()));
        }
        return roleMenus;
    }

    public UserVo login(UserLoginDto userLoginDto) {
        UserEntity dbUser = getUserInfo(userLoginDto.getUsername());
        if (dbUser != null) {
            boolean matches = passwordEncoder.matches(userLoginDto.getPassword(), dbUser.getPassword());
            if (!matches) {
                throw new BeHappyException(ORIGINAL_PASSWORD_NOT_CORRECT.getCode(), ORIGINAL_PASSWORD_NOT_CORRECT.getMsg());
            }
            // ??????
            StpUtil.login(dbUser.getId());
            UserVo userVo = BeanUtil.copyProperties(dbUser, UserVo.class);
            userVo.setToken(StpUtil.getTokenValue());
            userVo.setTokenPrefix(saTokenConfig.getTokenPrefix());
            userVo.setTokenType(StpUtil.getLoginType());
            String role = dbUser.getRole();
            // ???????????????????????????
            List<MenuVo> roleMenus = getRoleMenus(role);
            userVo.setMenus(roleMenus);
            return userVo;
        }
        throw new BeHappyException(USER_NOT_EXISTS.getCode(), USER_NOT_EXISTS.getMsg());
    }
}
